#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <set>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <unistd.h>
#include <android/log.h>
#include "llama.h"
#include "chat.h"
#include "sampling.h"
#include "nlohmann/json.hpp"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LlamaChatEngine"
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#define LOGE(...) ((void)0)
#endif

static constexpr int32_t CALLBACK_TOKEN_BATCH = 4;
static constexpr size_t CALLBACK_BYTE_BATCH = 64;
static constexpr int32_t PENALTY_LAST_N = 64;

// llama.cpp's own default is a fixed 4 threads (GGML_DEFAULT_N_THREADS), which underuses the
// big cores on modern phones and can even land decode on the little cluster. Both n_threads
// (single-token decode) and n_threads_batch (prompt prefill) are hardware-derived instead:
// half the online processor count — an approximation of the big-core share on typical ARM
// big.LITTLE topologies — clamped to [1, 6] so one local generation never starves the
// foreground UI, falling back to 4 when sysconf is unavailable.
static int32_t derive_thread_count() {
    long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpu_count <= 0) return 4;
    int32_t threads = static_cast<int32_t>(cpu_count) / 2;
    if (threads < 1) threads = 1;
    if (threads > 6) threads = 6;
    return threads;
}

struct ChatHandle {
    llama_model * model   = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    common_chat_templates_ptr chat_templates;
    std::string path;
    int32_t n_ctx = 0;
    std::atomic<bool> cancelled{false};
    std::vector<llama_token> decoded_tokens;
    mtmd_context * mtmd_ctx = nullptr;  // multimodal context (for vision models)
};

static bool abort_callback(void * data) {
    ChatHandle * handle = (ChatHandle *)data;
    return handle->cancelled.load(std::memory_order_relaxed);
}

static void clear_text_cache(ChatHandle * handle) {
    if (handle->ctx) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
    }
    handle->decoded_tokens.clear();
}

static size_t prepare_text_cache(
    ChatHandle * handle,
    const std::vector<llama_token> & prompt_tokens
) {
    size_t retained_prefix = 0;
    const size_t comparable = std::min(
        handle->decoded_tokens.size(), prompt_tokens.size()
    );
    while (retained_prefix < comparable &&
           handle->decoded_tokens[retained_prefix] == prompt_tokens[retained_prefix]) {
        retained_prefix++;
    }

    // Sampling needs logits from this request, so an exact prompt match must replay one token.
    if (retained_prefix == prompt_tokens.size() && retained_prefix > 0) {
        retained_prefix--;
    }

    llama_memory_t memory = llama_get_memory(handle->ctx);
    const bool removed = llama_memory_seq_rm(
        memory, 0, static_cast<llama_pos>(retained_prefix), -1
    );
    const llama_pos pos_min = llama_memory_seq_pos_min(memory, 0);
    const llama_pos pos_max = llama_memory_seq_pos_max(memory, 0);
    const bool memory_matches = retained_prefix == 0
        ? pos_max == -1
        : pos_min == 0 && pos_max + 1 == static_cast<llama_pos>(retained_prefix);
    if (!removed || !memory_matches) {
        clear_text_cache(handle);
        return 0;
    }
    handle->decoded_tokens.resize(retained_prefix);
    return retained_prefix;
}

// Returns the byte length of the largest prefix of `text` that ends on a
// complete UTF-8 character boundary. llama frequently splits a multi-byte glyph
// (CJK, Arabic/Persian, emoji, …) across token pieces, so a single piece may end
// with a truncated sequence. Handing those raw bytes to NewStringUTF aborts the
// VM ("input is not valid Modified UTF-8"), so callers buffer the incomplete tail
// until the next token completes it.
static size_t utf8_complete_prefix_len(const std::string & text) {
    size_t len = text.length();
    // A truncated lead byte can only be within the last 3 bytes of the buffer.
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(text[len - i]);
        if ((c & 0xE0) == 0xC0) return i < 2 ? len - i : len; // 2-byte sequence
        if ((c & 0xF0) == 0xE0) return i < 3 ? len - i : len; // 3-byte sequence
        if ((c & 0xF8) == 0xF0) return i < 4 ? len - i : len; // 4-byte sequence
        // ASCII or continuation byte: keep scanning back for the lead byte.
    }
    return len;
}

// Build a jstring from standard UTF-8 bytes WITHOUT going through NewStringUTF.
// NewStringUTF expects *Modified* UTF-8, in which supplementary-plane code points
// (U+10000+ — emoji, CJK extensions) must be a 6-byte CESU-8 surrogate pair; a
// standard 4-byte UTF-8 sequence is invalid Modified UTF-8 and aborts the VM. We
// decode UTF-8 → UTF-16 (emitting surrogate pairs) and use NewString, which takes
// genuine UTF-16 and handles the whole BMP + supplementary range safely.
static jstring utf8_to_jstring(JNIEnv * env, const char * data, size_t len) {
    std::vector<jchar> utf16;
    utf16.reserve(len);
    size_t i = 0;
    while (i < len) {
        unsigned char c = static_cast<unsigned char>(data[i]);
        uint32_t cp;
        size_t adv;
        if (c < 0x80) {
            cp = c; adv = 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            cp = (uint32_t(c & 0x1F) << 6) | (data[i + 1] & 0x3F); adv = 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            cp = (uint32_t(c & 0x0F) << 12) | (uint32_t(data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F); adv = 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            cp = (uint32_t(c & 0x07) << 18) | (uint32_t(data[i + 1] & 0x3F) << 12)
               | (uint32_t(data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F); adv = 4;
        } else {
            cp = 0xFFFD; adv = 1; // malformed lead/continuation → replacement char
        }
        i += adv;
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

struct NativeChatCallbacks {
    jclass clazz = nullptr;
    jmethodID on_text = nullptr;
    jmethodID on_thought = nullptr;
    jmethodID on_tool_call = nullptr;
    jmethodID on_tool_calls_complete = nullptr;
    jmethodID on_done = nullptr;
    jmethodID on_error = nullptr;
};

static bool init_callbacks(JNIEnv * env, jobject callback, NativeChatCallbacks & methods) {
    methods.clazz = env->GetObjectClass(callback);
    if (!methods.clazz) return false;
    methods.on_text = env->GetMethodID(methods.clazz, "onText", "(Ljava/lang/String;)Z");
    methods.on_thought = env->GetMethodID(
        methods.clazz, "onThought", "(Ljava/lang/String;)Z"
    );
    methods.on_tool_call = env->GetMethodID(
        methods.clazz, "onToolCall",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z"
    );
    methods.on_tool_calls_complete = env->GetMethodID(
        methods.clazz, "onToolCallsComplete", "()Z"
    );
    methods.on_done = env->GetMethodID(methods.clazz, "onDone", "(Ljava/lang/String;II)V");
    methods.on_error = env->GetMethodID(methods.clazz, "onError", "(Ljava/lang/String;II)V");
    if (methods.on_text && methods.on_thought && methods.on_tool_call &&
        methods.on_tool_calls_complete && methods.on_done && methods.on_error) return true;
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return false;
}

static jint report_error(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * message,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jmessage = utf8_to_jstring(env, message, std::strlen(message));
    env->CallVoidMethod(
        callback, methods.on_error, jmessage,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jmessage);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return -1;
}

static jint report_done(
    JNIEnv * env,
    jobject callback,
    NativeChatCallbacks & methods,
    const char * reason,
    int32_t input_tokens,
    int32_t output_tokens
) {
    jstring jreason = env->NewStringUTF(reason);
    env->CallVoidMethod(
        callback, methods.on_done, jreason,
        static_cast<jint>(input_tokens), static_cast<jint>(output_tokens)
    );
    env->DeleteLocalRef(jreason);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(methods.clazz);
    methods.clazz = nullptr;
    return output_tokens;
}

static bool report_string(
    JNIEnv * env,
    jobject callback,
    jmethodID method,
    const std::string & value
) {
    jstring jvalue = utf8_to_jstring(env, value.data(), value.size());
    jboolean accepted = env->CallBooleanMethod(callback, method, jvalue);
    env->DeleteLocalRef(jvalue);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

static bool report_tool_call(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods,
    size_t index,
    const common_chat_tool_call & call
) {
    jstring jid = utf8_to_jstring(env, call.id.data(), call.id.size());
    jstring jname = utf8_to_jstring(env, call.name.data(), call.name.size());
    jstring jarguments = utf8_to_jstring(
        env, call.arguments.data(), call.arguments.size()
    );
    jboolean accepted = env->CallBooleanMethod(
        callback, methods.on_tool_call, static_cast<jint>(index), jid, jname, jarguments
    );
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jarguments);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

static bool report_tool_calls_complete(
    JNIEnv * env,
    jobject callback,
    const NativeChatCallbacks & methods
) {
    jboolean accepted = env->CallBooleanMethod(callback, methods.on_tool_calls_complete);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return accepted == JNI_TRUE;
}

static bool token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & piece
) {
    char inline_buffer[256];
    int32_t length = llama_token_to_piece(
        vocab, token, inline_buffer, sizeof(inline_buffer), 0, true
    );
    if (length >= 0) {
        piece.assign(inline_buffer, static_cast<size_t>(length));
        return true;
    }
    std::vector<char> dynamic_buffer(static_cast<size_t>(-length));
    length = llama_token_to_piece(
        vocab, token, dynamic_buffer.data(), dynamic_buffer.size(), 0, true
    );
    if (length < 0) return false;
    piece.assign(dynamic_buffer.data(), static_cast<size_t>(length));
    return true;
}

struct TemplateSamplingMetadata {
    std::string prompt;
    std::string grammar;
    bool grammar_lazy = false;
    std::string generation_prompt;
    std::vector<common_grammar_trigger> grammar_triggers;
    std::vector<std::string> preserved_tokens;
    std::set<llama_token> preserved_token_ids;
    common_chat_format format = COMMON_CHAT_FORMAT_CONTENT_ONLY;
    std::string parser;
};

static bool read_java_string(JNIEnv * env, jstring value, std::string & result) {
    if (!value) {
        result.clear();
        return true;
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return false;
    result.assign(chars);
    env->ReleaseStringUTFChars(value, chars);
    return true;
}

static bool read_string_field(
    JNIEnv * env,
    jobject object,
    jclass object_class,
    const char * name,
    std::string & result
) {
    jfieldID field = env->GetFieldID(object_class, name, "Ljava/lang/String;");
    if (!field) return false;
    jstring value = static_cast<jstring>(env->GetObjectField(object, field));
    const bool ok = read_java_string(env, value, result);
    if (value) env->DeleteLocalRef(value);
    return ok;
}

static jobject make_template_result(
    JNIEnv * env,
    const common_chat_params & params,
    bool supports_tools
) {
    jclass trigger_class = env->FindClass(
        "com/newoether/agora/api/ChatTemplateGrammarTrigger"
    );
    jclass result_class = env->FindClass(
        "com/newoether/agora/api/LlamaChatTemplateResult"
    );
    jclass string_class = env->FindClass("java/lang/String");
    if (!trigger_class || !result_class || !string_class) return nullptr;

    jmethodID trigger_ctor = env->GetMethodID(trigger_class, "<init>", "(ILjava/lang/String;I)V");
    jmethodID result_ctor = env->GetMethodID(
        result_class,
        "<init>",
        "(Ljava/lang/String;ZLjava/lang/String;ZLjava/lang/String;"
        "[Lcom/newoether/agora/api/ChatTemplateGrammarTrigger;[Ljava/lang/String;I"
        "Ljava/lang/String;)V"
    );
    if (!trigger_ctor || !result_ctor) return nullptr;

    jobjectArray triggers = env->NewObjectArray(
        static_cast<jsize>(params.grammar_triggers.size()), trigger_class, nullptr
    );
    jobjectArray preserved = env->NewObjectArray(
        static_cast<jsize>(params.preserved_tokens.size()), string_class, nullptr
    );
    if (!triggers || !preserved) return nullptr;

    for (jsize i = 0; i < static_cast<jsize>(params.grammar_triggers.size()); ++i) {
        const auto & trigger = params.grammar_triggers[static_cast<size_t>(i)];
        jstring value = utf8_to_jstring(env, trigger.value.data(), trigger.value.size());
        jobject item = env->NewObject(
            trigger_class, trigger_ctor,
            static_cast<jint>(trigger.type), value, static_cast<jint>(trigger.token)
        );
        env->SetObjectArrayElement(triggers, i, item);
        env->DeleteLocalRef(item);
        env->DeleteLocalRef(value);
    }
    for (jsize i = 0; i < static_cast<jsize>(params.preserved_tokens.size()); ++i) {
        const auto & token = params.preserved_tokens[static_cast<size_t>(i)];
        jstring value = utf8_to_jstring(env, token.data(), token.size());
        env->SetObjectArrayElement(preserved, i, value);
        env->DeleteLocalRef(value);
    }

    jstring prompt = utf8_to_jstring(env, params.prompt.data(), params.prompt.size());
    jstring grammar = utf8_to_jstring(env, params.grammar.data(), params.grammar.size());
    jstring generation_prompt = utf8_to_jstring(
        env, params.generation_prompt.data(), params.generation_prompt.size()
    );
    jstring parser = utf8_to_jstring(env, params.parser.data(), params.parser.size());
    jobject result = env->NewObject(
        result_class, result_ctor,
        prompt, supports_tools ? JNI_TRUE : JNI_FALSE,
        grammar, params.grammar_lazy ? JNI_TRUE : JNI_FALSE,
        generation_prompt, triggers, preserved,
        static_cast<jint>(params.format), parser
    );
    env->DeleteLocalRef(prompt);
    env->DeleteLocalRef(grammar);
    env->DeleteLocalRef(generation_prompt);
    env->DeleteLocalRef(parser);
    env->DeleteLocalRef(triggers);
    env->DeleteLocalRef(preserved);
    env->DeleteLocalRef(trigger_class);
    env->DeleteLocalRef(result_class);
    env->DeleteLocalRef(string_class);
    return result;
}

static bool read_template_metadata(
    JNIEnv * env,
    jobject template_result,
    TemplateSamplingMetadata & metadata
) {
    if (!template_result) return false;
    jclass result_class = env->GetObjectClass(template_result);
    if (!result_class) return false;
    const bool strings_ok =
        read_string_field(env, template_result, result_class, "prompt", metadata.prompt) &&
        read_string_field(env, template_result, result_class, "grammar", metadata.grammar) &&
        read_string_field(
            env, template_result, result_class, "generationPrompt", metadata.generation_prompt
        ) &&
        read_string_field(env, template_result, result_class, "parser", metadata.parser);
    jfieldID lazy_field = env->GetFieldID(result_class, "grammarLazy", "Z");
    jfieldID format_field = env->GetFieldID(result_class, "format", "I");
    jfieldID triggers_field = env->GetFieldID(
        result_class, "grammarTriggers",
        "[Lcom/newoether/agora/api/ChatTemplateGrammarTrigger;"
    );
    jfieldID preserved_field = env->GetFieldID(
        result_class, "preservedTokens", "[Ljava/lang/String;"
    );
    if (!strings_ok || !lazy_field || !format_field || !triggers_field || !preserved_field) {
        env->DeleteLocalRef(result_class);
        return false;
    }
    const jint format = env->GetIntField(template_result, format_field);
    if (format < COMMON_CHAT_FORMAT_CONTENT_ONLY || format >= COMMON_CHAT_FORMAT_COUNT) {
        env->DeleteLocalRef(result_class);
        return false;
    }
    metadata.format = static_cast<common_chat_format>(format);
    metadata.grammar_lazy = env->GetBooleanField(template_result, lazy_field) == JNI_TRUE;
    jobjectArray triggers = static_cast<jobjectArray>(
        env->GetObjectField(template_result, triggers_field)
    );
    jobjectArray preserved = static_cast<jobjectArray>(
        env->GetObjectField(template_result, preserved_field)
    );
    if (!triggers || !preserved) {
        env->DeleteLocalRef(result_class);
        return false;
    }

    const jsize trigger_count = env->GetArrayLength(triggers);
    metadata.grammar_triggers.reserve(static_cast<size_t>(trigger_count));
    for (jsize i = 0; i < trigger_count; ++i) {
        jobject trigger = env->GetObjectArrayElement(triggers, i);
        jclass trigger_class = env->GetObjectClass(trigger);
        jfieldID type_field = env->GetFieldID(trigger_class, "type", "I");
        jfieldID value_field = env->GetFieldID(trigger_class, "value", "Ljava/lang/String;");
        jfieldID token_field = env->GetFieldID(trigger_class, "token", "I");
        if (!type_field || !value_field || !token_field) return false;
        const jint type = env->GetIntField(trigger, type_field);
        if (type < COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN ||
            type > COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL) return false;
        jstring value = static_cast<jstring>(env->GetObjectField(trigger, value_field));
        std::string trigger_value;
        if (!read_java_string(env, value, trigger_value)) return false;
        metadata.grammar_triggers.push_back({
            static_cast<common_grammar_trigger_type>(type),
            std::move(trigger_value),
            static_cast<llama_token>(env->GetIntField(trigger, token_field)),
        });
        if (value) env->DeleteLocalRef(value);
        env->DeleteLocalRef(trigger_class);
        env->DeleteLocalRef(trigger);
    }

    const jsize preserved_count = env->GetArrayLength(preserved);
    metadata.preserved_tokens.reserve(static_cast<size_t>(preserved_count));
    for (jsize i = 0; i < preserved_count; ++i) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(preserved, i));
        std::string token;
        if (!read_java_string(env, value, token)) return false;
        metadata.preserved_tokens.push_back(std::move(token));
        if (value) env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(triggers);
    env->DeleteLocalRef(preserved);
    env->DeleteLocalRef(result_class);
    return true;
}

struct NativeChatParser {
    common_chat_parser_params params;
    common_chat_msg message;
    std::string generated_text;
    std::string initialization_error;

    explicit NativeChatParser(const TemplateSamplingMetadata & metadata) {
        params.format = metadata.format;
        params.generation_prompt = metadata.generation_prompt;
        params.parse_tool_calls = true;
        if (metadata.parser.empty()) {
            if (metadata.format != COMMON_CHAT_FORMAT_CONTENT_ONLY) {
                initialization_error = "Chat template parser metadata is missing";
            }
            return;
        }
        try {
            params.parser.load(metadata.parser);
        } catch (const std::exception & exception) {
            initialization_error = exception.what();
        }
    }

    bool update(
        JNIEnv * env,
        jobject callback,
        const NativeChatCallbacks & methods,
        const char * data,
        size_t length,
        bool is_partial,
        std::string & error
    ) {
        if (!initialization_error.empty()) {
            error = initialization_error;
            return false;
        }
        generated_text.append(data, length);
        try {
            common_chat_msg next = common_chat_parse(generated_text, is_partial, params);
            if (next.empty()) {
                if (!is_partial) message = {};
                return true;
            }
            const auto diffs = common_chat_msg_diff::compute_diffs(message, next);
            message = std::move(next);
            for (const auto & diff : diffs) {
                if (!diff.reasoning_content_delta.empty() && !report_string(
                        env, callback, methods.on_thought,
                        diff.reasoning_content_delta
                    )) return false;
                if (!diff.content_delta.empty() && !report_string(
                        env, callback, methods.on_text, diff.content_delta
                    )) return false;
                if (diff.tool_call_index != std::string::npos) {
                    if (diff.tool_call_index >= message.tool_calls.size()) {
                        error = "Parsed tool call index is out of range";
                        return false;
                    }
                    if (!report_tool_call(
                            env, callback, methods,
                            diff.tool_call_index,
                            message.tool_calls[diff.tool_call_index]
                        )) return false;
                }
            }
            return true;
        } catch (const std::exception & exception) {
            error = exception.what();
            return false;
        }
    }

    bool finish(
        JNIEnv * env,
        jobject callback,
        const NativeChatCallbacks & methods,
        std::string & error
    ) {
        if (!update(env, callback, methods, "", 0, false, error)) return false;
        if (message.tool_calls.empty()) return true;
        for (const auto & call : message.tool_calls) {
            if (call.name.empty()) {
                error = "Parsed tool call is missing a name";
                return false;
            }
            try {
                const auto arguments = nlohmann::ordered_json::parse(
                    call.arguments.empty() ? "{}" : call.arguments
                );
                if (!arguments.is_object()) {
                    error = "Parsed tool call arguments are not a JSON object";
                    return false;
                }
            } catch (const std::exception & exception) {
                error = std::string("Parsed tool call arguments are incomplete: ") +
                    exception.what();
                return false;
            }
        }
        return report_tool_calls_complete(env, callback, methods);
    }
};

static common_sampler * init_chat_sampler(
    ChatHandle * handle,
    TemplateSamplingMetadata & metadata,
    float temperature,
    float top_p,
    float frequency_penalty,
    float presence_penalty,
    std::string & error
) {
    common_params_sampling params;
    params.samplers = {
        COMMON_SAMPLER_TYPE_PENALTIES,
        COMMON_SAMPLER_TYPE_MIN_P,
        COMMON_SAMPLER_TYPE_TOP_P,
        COMMON_SAMPLER_TYPE_TEMPERATURE,
    };
    params.penalty_last_n = PENALTY_LAST_N;
    params.penalty_repeat = 1.0f;
    params.penalty_freq = frequency_penalty;
    params.penalty_present = presence_penalty;
    params.min_p = 0.05f;
    params.min_keep = 1;
    params.top_p = top_p;
    params.temp = temperature;
    if (!metadata.grammar.empty()) {
        params.grammar = { COMMON_GRAMMAR_TYPE_TOOL_CALLS, metadata.grammar };
    }
    params.grammar_lazy = metadata.grammar_lazy;
    params.generation_prompt = metadata.generation_prompt;
    for (const auto & value : metadata.preserved_tokens) {
        const auto tokens = common_tokenize(handle->vocab, value, false, true);
        if (tokens.size() == 1) {
            params.preserved_tokens.insert(tokens.front());
            metadata.preserved_token_ids.insert(tokens.front());
        }
    }
    for (const auto & source : metadata.grammar_triggers) {
        common_grammar_trigger trigger = source;
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD: {
                const auto tokens = common_tokenize(handle->vocab, trigger.value, false, true);
                if (tokens.size() == 1) {
                    if (metadata.preserved_token_ids.find(tokens.front()) ==
                        metadata.preserved_token_ids.end()) {
                        error = "Grammar trigger word is not a preserved token";
                        return nullptr;
                    }
                    trigger.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    trigger.token = tokens.front();
                }
                break;
            }
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (metadata.preserved_token_ids.find(trigger.token) ==
                    metadata.preserved_token_ids.end()) {
                    error = "Grammar trigger token is not preserved";
                    return nullptr;
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                break;
            default:
                error = "Unknown grammar trigger type";
                return nullptr;
        }
        params.grammar_triggers.push_back(std::move(trigger));
    }
    if (params.grammar_lazy && params.grammar_triggers.empty()) {
        error = "Lazy grammar requires at least one trigger";
        return nullptr;
    }
    try {
        common_sampler * sampler = common_sampler_init(handle->model, params);
        if (!sampler) error = "Unable to initialize chat sampler";
        return sampler;
    } catch (const std::exception & exception) {
        error = exception.what();
        return nullptr;
    }
}

static bool is_preserved_token(
    const TemplateSamplingMetadata & metadata,
    llama_token token
) {
    return metadata.preserved_token_ids.find(token) != metadata.preserved_token_ids.end();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadModel(
    JNIEnv * env, jclass /*clazz*/, jstring path, jint n_ctx) {

    const char * path_str = env->GetStringUTFChars(path, nullptr);
    if (!path_str) return 0;

    ChatHandle * handle = new ChatHandle();
    if (!handle) {
        env->ReleaseStringUTFChars(path, path_str);
        return 0;
    }

    const auto load_started = std::chrono::steady_clock::now();
    llama_model_params model_params = llama_model_default_params();
    const auto model_started = std::chrono::steady_clock::now();
    handle->model = llama_model_load_from_file(path_str, model_params);
    const auto model_finished = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(path, path_str);

    if (!handle->model) {
        LOGE("Failed to load model from file");
        delete handle;
        return 0;
    }

    handle->vocab = llama_model_get_vocab(handle->model);
    handle->n_ctx = n_ctx;

    try {
        handle->chat_templates = common_chat_templates_init(handle->model, "");
        if (!common_chat_templates_was_explicit(handle->chat_templates.get())) {
            handle->chat_templates.reset();
            LOGE("Model does not contain an explicit chat template");
        }
    } catch (const std::exception &) {
        handle->chat_templates.reset();
        LOGE("Failed to initialize model chat template");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx   = n_ctx;
    // Hardware-derived parallelism for both single-token decode and batch prefill; see
    // derive_thread_count(). Stays constant for the context's lifetime, so resident
    // identity (path + n_ctx) is unchanged.
    const int32_t derived_threads = derive_thread_count();
    ctx_params.n_threads      = derived_threads;
    ctx_params.n_threads_batch = derived_threads;
    // n_batch bounds the LOGITS/EMBEDDINGS buffers llama.cpp allocates up front, so tying it to
    // n_ctx made memory grow with the square of the context — the OOM on large-context local
    // models (#53). 512 is llama.cpp's own default and prefill is chunked to match; on-device
    // prompt-eval throughput is unaffected because it is compute-bound well below 512 tokens.
    ctx_params.n_batch = std::min(512, n_ctx);

    const auto context_started = std::chrono::steady_clock::now();
    handle->ctx = llama_init_from_model(handle->model, ctx_params);
    if (!handle->ctx) {
        LOGE("Failed to create context");
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }

    llama_set_abort_callback(handle->ctx, abort_callback, handle);

    const auto load_finished = std::chrono::steady_clock::now();
    const auto model_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        model_finished - model_started
    ).count();
    const auto context_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - context_started
    ).count();
    const auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        load_finished - load_started
    ).count();
    LOGD("Chat load: model_ms=%lld, context_ms=%lld, total_ms=%lld, n_ctx=%d, n_ctx_train=%d, n_threads=%d",
         (long long)model_ms, (long long)context_ms, (long long)total_ms,
         n_ctx, llama_model_n_ctx_train(handle->model), derived_threads);

    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGetTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return nullptr;

    const char * tmpl = llama_model_chat_template(handle->model, nullptr);
    if (!tmpl) return nullptr;
    return utf8_to_jstring(env, tmpl, strlen(tmpl));
}

JNIEXPORT jobject JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatApplyTemplate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jobject request) {

    if (!handle_ptr || !request) return nullptr;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model || !handle->chat_templates) return nullptr;

    jclass request_class = env->GetObjectClass(request);
    jfieldID messages_field = env->GetFieldID(
        request_class, "messages", "[Lcom/newoether/agora/api/ChatTemplateMessage;"
    );
    jfieldID tools_field = env->GetFieldID(
        request_class, "tools", "[Lcom/newoether/agora/api/ChatTemplateTool;"
    );
    jfieldID add_generation_field = env->GetFieldID(
        request_class, "addGenerationPrompt", "Z"
    );
    jfieldID thinking_field = env->GetFieldID(request_class, "enableThinking", "Z");
    if (!messages_field || !tools_field || !add_generation_field || !thinking_field) {
        env->DeleteLocalRef(request_class);
        return nullptr;
    }

    jobjectArray messages = static_cast<jobjectArray>(env->GetObjectField(request, messages_field));
    jobjectArray tools = static_cast<jobjectArray>(env->GetObjectField(request, tools_field));
    if (!messages || !tools) {
        env->DeleteLocalRef(request_class);
        return nullptr;
    }

    common_chat_templates_inputs inputs;
    const jint message_count = env->GetArrayLength(messages);
    const jint tool_count = env->GetArrayLength(tools);
    inputs.messages.reserve(static_cast<size_t>(message_count));
    inputs.tools.reserve(static_cast<size_t>(tool_count));
    inputs.add_generation_prompt =
        env->GetBooleanField(request, add_generation_field) == JNI_TRUE;
    inputs.enable_thinking = env->GetBooleanField(request, thinking_field) == JNI_TRUE;
    inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    inputs.parallel_tool_calls = true;
    inputs.use_jinja = true;
    bool has_tool_history = false;

    for (jint i = 0; i < message_count; ++i) {
        jobject message = env->GetObjectArrayElement(messages, i);
        jclass message_class = env->GetObjectClass(message);
        common_chat_msg chat_message;
        if (!read_string_field(env, message, message_class, "role", chat_message.role) ||
            !read_string_field(env, message, message_class, "content", chat_message.content) ||
            !read_string_field(env, message, message_class, "toolName", chat_message.tool_name) ||
            !read_string_field(
                env, message, message_class, "toolCallId", chat_message.tool_call_id
            )) {
            env->DeleteLocalRef(message_class);
            env->DeleteLocalRef(message);
            env->DeleteLocalRef(messages);
            env->DeleteLocalRef(tools);
            env->DeleteLocalRef(request_class);
            return nullptr;
        }
        jfieldID calls_field = env->GetFieldID(
            message_class, "toolCalls",
            "[Lcom/newoether/agora/api/ChatTemplateToolCall;"
        );
        jobjectArray calls = static_cast<jobjectArray>(
            env->GetObjectField(message, calls_field)
        );
        const jsize call_count = calls ? env->GetArrayLength(calls) : 0;
        chat_message.tool_calls.reserve(static_cast<size_t>(call_count));
        for (jsize call_index = 0; call_index < call_count; ++call_index) {
            jobject call = env->GetObjectArrayElement(calls, call_index);
            jclass call_class = env->GetObjectClass(call);
            common_chat_tool_call tool_call;
            const bool ok =
                read_string_field(env, call, call_class, "id", tool_call.id) &&
                read_string_field(env, call, call_class, "name", tool_call.name) &&
                read_string_field(
                    env, call, call_class, "arguments", tool_call.arguments
                );
            env->DeleteLocalRef(call_class);
            env->DeleteLocalRef(call);
            if (!ok) return nullptr;
            chat_message.tool_calls.push_back(std::move(tool_call));
        }
        if (calls) env->DeleteLocalRef(calls);
        has_tool_history = has_tool_history || !chat_message.tool_calls.empty() ||
            chat_message.role == "tool";
        inputs.messages.push_back(std::move(chat_message));
        env->DeleteLocalRef(message_class);
        env->DeleteLocalRef(message);
    }

    for (jint i = 0; i < tool_count; ++i) {
        jobject tool = env->GetObjectArrayElement(tools, i);
        jclass tool_class = env->GetObjectClass(tool);
        common_chat_tool chat_tool;
        const bool ok =
            read_string_field(env, tool, tool_class, "name", chat_tool.name) &&
            read_string_field(env, tool, tool_class, "description", chat_tool.description) &&
            read_string_field(env, tool, tool_class, "parameters", chat_tool.parameters);
        env->DeleteLocalRef(tool_class);
        env->DeleteLocalRef(tool);
        if (!ok) return nullptr;
        inputs.tools.push_back(std::move(chat_tool));
    }

    env->DeleteLocalRef(messages);
    env->DeleteLocalRef(tools);
    env->DeleteLocalRef(request_class);

    const bool needs_tool_template = !inputs.tools.empty() || has_tool_history;
    // Probe the same template the apply path will render with: a split-template model
    // (separate chat_template.tool_use) must be judged by its tool_use template's caps,
    // not by the default template, which never touches tool fields.
    const auto caps = common_chat_templates_get_caps(handle->chat_templates.get(), needs_tool_template);
    const auto supports = [&](const char * name) {
        const auto found = caps.find(name);
        return found != caps.end() && found->second;
    };
    const bool supports_tools = supports("supports_tools") && supports("supports_tool_calls");
    if (needs_tool_template && !supports_tools) {
        LOGE("Chat template rejected: supports_tools=%d supports_tool_calls=%d "
             "supports_object_arguments=%d tools=%d tool_history=%d",
             supports("supports_tools"), supports("supports_tool_calls"),
             supports("supports_object_arguments"),
             static_cast<int>(inputs.tools.size()), has_tool_history ? 1 : 0);
        return make_template_result(env, common_chat_params{}, false);
    }

    try {
        const common_chat_params params = common_chat_templates_apply(
            handle->chat_templates.get(), inputs
        );
        return make_template_result(env, params, supports_tools);
    } catch (const std::exception &) {
        LOGE("Failed to apply model chat template");
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerate(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobject template_result, jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) return report_error(
        env, callback, callbacks, "Model is not loaded", 0, 0
    );

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    TemplateSamplingMetadata metadata;
    if (!read_template_metadata(env, template_result, metadata)) {
        return report_error(env, callback, callbacks, "Unable to read chat template", 0, 0);
    }
    const std::string & prompt_text = metadata.prompt;

    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    int32_t n_tokens_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_tokens_max);
    int32_t n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                       prompt_text.size(), tokens.data(),
                                       n_tokens_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(handle->vocab, prompt_text.c_str(),
                                   prompt_text.size(), tokens.data(),
                                   -n_tokens, true, true);
    }
    if (n_tokens <= 0) {
        LOGE("Tokenization returned 0 tokens for prompt len=%zu", prompt_text.size());
        return report_error(env, callback, callbacks, "Tokenization failed", 0, 0);
    }
    tokens.resize(n_tokens);

    const int32_t n_ctx = llama_n_ctx(handle->ctx);
    const int32_t min_generation_room = 4;
    if (n_tokens + min_generation_room > n_ctx) {
        LOGE("Prompt too long: prompt=%d + reserved=%d > ctx=%d",
             n_tokens, min_generation_room, n_ctx);
        char error_msg[64];
        std::snprintf(error_msg, sizeof(error_msg),
                      "LOCAL_CONTEXT_EXCEEDED:%d:%d", n_tokens, n_ctx);
        return report_error(env, callback, callbacks, error_msg, n_tokens, 0);
    }

    LOGD("Generating: prompt_len=%zu, n_tokens=%d, max_tokens=%d",
         prompt_text.size(), n_tokens, max_tokens);

    std::string sampler_error;
    common_sampler * smpl = init_chat_sampler(
        handle, metadata, temperature, top_p,
        frequency_penalty, presence_penalty, sampler_error
    );
    if (!smpl) {
        const char * message = sampler_error.empty()
            ? "Unable to initialize chat sampler"
            : sampler_error.c_str();
        return report_error(env, callback, callbacks, message, 0, 0);
    }

    const int32_t cached_tokens = static_cast<int32_t>(prepare_text_cache(handle, tokens));
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(handle->ctx));
    int32_t input_tokens = cached_tokens;
    const auto prefill_started = std::chrono::steady_clock::now();
    for (int32_t off = cached_tokens; off < n_tokens; off += n_batch) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Cancelled during prefill at %d/%d tokens", off, n_tokens);
            common_sampler_free(smpl);
            return report_done(env, callback, callbacks, "cancelled", input_tokens, 0);
        }
        const int32_t chunk = std::min(n_batch, n_tokens - off);
        llama_batch batch = llama_batch_get_one(tokens.data() + off, chunk);
        const int32_t decode_result = llama_decode(handle->ctx, batch);
        if (decode_result != 0) {
            const bool was_cancelled =
                handle->cancelled.load(std::memory_order_relaxed);
            LOGE("Prefill decode failed at offset %d (chunk=%d, code=%d)",
                 off, chunk, decode_result);
            clear_text_cache(handle);
            common_sampler_free(smpl);
            if (was_cancelled) {
                return report_done(
                    env, callback, callbacks, "cancelled", input_tokens, 0
                );
            }
            return report_error(
                env, callback, callbacks, "Prefill decode failed", input_tokens, 0
            );
        }
        handle->decoded_tokens.insert(
            handle->decoded_tokens.end(), tokens.begin() + off, tokens.begin() + off + chunk
        );
        input_tokens += chunk;
    }
    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Text prefill: input_tokens=%d, cached_tokens=%d, processed_tokens=%d, duration_ms=%lld",
         input_tokens, cached_tokens, input_tokens - cached_tokens,
         (long long)prefill_ms);

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    NativeChatParser parser(metadata);
    const char * stop_reason = nullptr;
    std::string failure;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            LOGD("Generation cancelled at %d tokens", generated);
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            LOGD("Context full at %d tokens", generated);
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = common_sampler_sample(smpl, handle->ctx, -1);
        common_sampler_accept(smpl, new_token_id, true);

        if (llama_vocab_is_eog(handle->vocab, new_token_id) &&
            !is_preserved_token(metadata, new_token_id)) {
            LOGD("EOG token %d at position %d", new_token_id, generated);
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            LOGE("llama_token_to_piece failed");
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        const int32_t decode_result = llama_decode(handle->ctx, single);
        if (decode_result != 0) {
            const bool was_cancelled =
                handle->cancelled.load(std::memory_order_relaxed);
            LOGE("Decode failed at token %d (code=%d)", generated + 1, decode_result);
            clear_text_cache(handle);
            if (was_cancelled) stop_reason = "cancelled";
            else failure = "Decode failed";
            break;
        }
        handle->decoded_tokens.push_back(new_token_id);
        generated++;

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!parser.update(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len, true, failure
                )) {
                if (failure.empty()) failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (failure.empty() && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!parser.update(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size(), true, failure
            )) {
            if (failure.empty()) failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (failure.empty() && !utf8_pending.empty()) {
        failure = "Generated incomplete UTF-8 output";
    }
    if (failure.empty() && !consumer_closed && stop_reason &&
        std::strcmp(stop_reason, "cancelled") != 0 &&
        !parser.finish(env, callback, callbacks, failure)) {
        if (failure.empty()) failure = "Stream consumer closed";
        consumer_closed = true;
    }
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Text decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure.empty() ? stop_reason : "error");
    LOGD("Text request: input_tokens=%d, output_tokens=%d, total_ms=%lld",
         input_tokens, generated, (long long)request_ms);
    common_sampler_free(smpl);
    if (!failure.empty()) {
        return report_error(
            env, callback, callbacks, failure.c_str(), input_tokens, generated
        );
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatLoadMmproj(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr, jstring mmproj_path) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->model) return JNI_FALSE;

    const char * mmproj_str = env->GetStringUTFChars(mmproj_path, nullptr);
    if (!mmproj_str) return JNI_FALSE;

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = 4;
    params.print_timings = false;

    // Try loading new mmproj first (don't free old one yet)
    mtmd_context * new_mtmd = mtmd_init_from_file(mmproj_str, handle->model, params);
    env->ReleaseStringUTFChars(mmproj_path, mmproj_str);

    if (!new_mtmd) {
        LOGE("Failed to load mmproj, keeping previous if any");
        return JNI_FALSE;
    }

    // Success: free old, install new
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
    }
    handle->mtmd_ctx = new_mtmd;

    LOGD("mmproj loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatUnloadMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (handle->mtmd_ctx) {
        mtmd_free(handle->mtmd_ctx);
        handle->mtmd_ctx = nullptr;
        LOGD("mmproj unloaded");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatHasMmproj(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return JNI_FALSE;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    return handle->mtmd_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatGenerateWithImages(
    JNIEnv * env, jclass /*clazz*/, jlong handle_ptr,
    jobject template_result, jobjectArray image_paths,
    jfloat temperature, jfloat top_p,
    jfloat frequency_penalty, jfloat presence_penalty, jint max_tokens,
    jobject callback) {

    NativeChatCallbacks callbacks;
    if (!init_callbacks(env, callback, callbacks)) return -1;
    if (!handle_ptr) {
        return report_error(env, callback, callbacks, "Invalid model handle", 0, 0);
    }
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    if (!handle->ctx || !handle->vocab) {
        return report_error(env, callback, callbacks, "Model is not loaded", 0, 0);
    }
    if (!handle->mtmd_ctx) {
        return report_error(
            env, callback, callbacks,
            "Vision projector not loaded. Add mmproj file in model settings.", 0, 0
        );
    }

    handle->cancelled.store(false, std::memory_order_relaxed);
    const auto request_started = std::chrono::steady_clock::now();

    TemplateSamplingMetadata metadata;
    if (!read_template_metadata(env, template_result, metadata)) {
        return report_error(env, callback, callbacks, "Unable to read chat template", 0, 0);
    }
    const std::string & prompt_text = metadata.prompt;
    if (prompt_text.empty()) {
        return report_error(env, callback, callbacks, "Prompt is empty", 0, 0);
    }

    const auto prefill_started = std::chrono::steady_clock::now();

    // --- Build bitmaps from image paths ---
    jint n_images = env->GetArrayLength(image_paths);
    std::vector<mtmd_bitmap *> bitmaps(n_images, nullptr);
    std::vector<std::string> image_path_storage(n_images);

    for (jint i = 0; i < n_images; i++) {
        jstring jpath = (jstring)env->GetObjectArrayElement(image_paths, i);
        if (!jpath) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        const char * cpath = env->GetStringUTFChars(jpath, nullptr);
        if (!cpath) {
            env->DeleteLocalRef(jpath);
            if (env->ExceptionCheck()) env->ExceptionClear();
            for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
            return report_error(
                env, callback, callbacks, "Unable to read image path.", 0, 0
            );
        }
        image_path_storage[i] = std::string(cpath);
        env->ReleaseStringUTFChars(jpath, cpath);
        env->DeleteLocalRef(jpath);

        bitmaps[i] = mtmd_helper_bitmap_init_from_file(handle->mtmd_ctx,
                                                       image_path_storage[i].c_str());
        if (!bitmaps[i]) {
            LOGE("Failed to load image at index %d of %d", i, n_images);
            // Clean up already-loaded bitmaps
            for (jint j = 0; j < i; j++) {
                if (bitmaps[j]) mtmd_bitmap_free(bitmaps[j]);
            }
            return report_error(
                env, callback, callbacks, "Failed to load image for multimodal input.", 0, 0
            );
        }
    }

    // --- Tokenize prompt with image markers ---
    mtmd_input_text text_input;
    text_input.text         = prompt_text.c_str();
    text_input.add_special  = true;
    text_input.parse_special = true;

    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    for (auto & b : bitmaps) bitmap_ptrs.push_back(b);

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        return report_error(
            env, callback, callbacks, "Unable to allocate multimodal prompt chunks.", 0, 0
        );
    }
    int32_t tok_ret = mtmd_tokenize(handle->mtmd_ctx, chunks, &text_input,
                                    bitmap_ptrs.data(), bitmap_ptrs.size());
    if (tok_ret != 0) {
        LOGE("mtmd_tokenize failed with code %d (images=%d)", tok_ret, n_images);
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_error(
            env, callback, callbacks, "Failed to tokenize multimodal prompt.", 0, 0
        );
    }

    llama_pos n_past = 0;
    int32_t n_ctx = llama_n_ctx(handle->ctx);
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
        mtmd_input_chunks_free(chunks);
        return report_done(env, callback, callbacks, "cancelled", 0, 0);
    }
    // The 6th argument is the helper's BATCH size, not the context size: passing n_ctx made it
    // build batches larger than the context's n_batch, which llama_decode rejects.
    clear_text_cache(handle);
    int32_t eval_ret = mtmd_helper_eval_chunks(handle->mtmd_ctx, handle->ctx,
                                                chunks, n_past, 0,
                                                static_cast<int32_t>(llama_n_batch(handle->ctx)),
                                                true, &n_past);
    // Free bitmaps and chunks after evaluation
    for (auto & b : bitmaps) if (b) mtmd_bitmap_free(b);
    mtmd_input_chunks_free(chunks);

    if (eval_ret != 0) {
        LOGE("mtmd_helper_eval_chunks failed with code %d", eval_ret);
        clear_text_cache(handle);
        return report_error(
            env, callback, callbacks, "Multimodal prefill failed.",
            static_cast<int32_t>(n_past), 0
        );
    }
    if (handle->cancelled.load(std::memory_order_relaxed)) {
        clear_text_cache(handle);
        return report_done(
            env, callback, callbacks, "cancelled", static_cast<int32_t>(n_past), 0
        );
    }

    const auto prefill_finished = std::chrono::steady_clock::now();
    const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        prefill_finished - prefill_started
    ).count();
    LOGD("Multimodal prefill: input_tokens=%lld, images=%d, duration_ms=%lld",
         (long long)n_past, n_images, (long long)prefill_ms);

    // --- Generation loop (same as text-only path) ---
    std::string sampler_error;
    common_sampler * smpl = init_chat_sampler(
        handle, metadata, temperature, top_p,
        frequency_penalty, presence_penalty, sampler_error
    );
    if (!smpl) {
        const char * message = sampler_error.empty()
            ? "Unable to initialize chat sampler"
            : sampler_error.c_str();
        clear_text_cache(handle);
        return report_error(
            env, callback, callbacks, message, static_cast<int32_t>(n_past), 0
        );
    }

    const int32_t context_after_prefill =
        llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
    const int32_t remaining_context = std::max(0, n_ctx - context_after_prefill);
    const int32_t generation_limit = std::min(max_tokens, remaining_context);
    const bool context_limited = generation_limit < max_tokens;

    int32_t generated = 0;
    int32_t callback_tokens = 0;
    std::string utf8_pending;
    std::string callback_buffer;
    NativeChatParser parser(metadata);
    const char * stop_reason = nullptr;
    std::string failure;
    bool consumer_closed = false;
    const auto decode_started = std::chrono::steady_clock::now();
    while (generated < generation_limit) {
        if (handle->cancelled.load(std::memory_order_relaxed)) {
            stop_reason = "cancelled";
            break;
        }

        int32_t n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), 0) + 1;
        if (n_ctx_used + 1 > n_ctx) {
            stop_reason = "context_full";
            break;
        }

        llama_token new_token_id = common_sampler_sample(smpl, handle->ctx, -1);
        common_sampler_accept(smpl, new_token_id, true);

        if (llama_vocab_is_eog(handle->vocab, new_token_id) &&
            !is_preserved_token(metadata, new_token_id)) {
            stop_reason = "eog";
            break;
        }

        std::string piece;
        if (!token_to_piece(handle->vocab, new_token_id, piece)) {
            failure = "Token conversion failed";
            break;
        }

        llama_batch single = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(handle->ctx, single) != 0) {
            failure = "Decode failed";
            break;
        }
        generated++;

        utf8_pending.append(piece);
        const size_t complete_len = utf8_complete_prefix_len(utf8_pending);
        if (complete_len > 0) {
            callback_buffer.append(utf8_pending.data(), complete_len);
            utf8_pending.erase(0, complete_len);
        }
        callback_tokens++;
        while (!callback_buffer.empty() &&
               (callback_tokens >= CALLBACK_TOKEN_BATCH ||
                callback_buffer.size() >= CALLBACK_BYTE_BATCH)) {
            size_t emit_len = std::min(callback_buffer.size(), CALLBACK_BYTE_BATCH);
            while (emit_len < callback_buffer.size() && emit_len > 0 &&
                   (static_cast<unsigned char>(callback_buffer[emit_len]) & 0xC0) == 0x80) {
                emit_len--;
            }
            if (!parser.update(
                    env, callback, callbacks,
                    callback_buffer.data(), emit_len, true, failure
                )) {
                if (failure.empty()) failure = "Stream consumer closed";
                consumer_closed = true;
                break;
            }
            callback_buffer.erase(0, emit_len);
            callback_tokens = 0;
        }
        if (consumer_closed) break;
    }

    if (failure.empty() && !stop_reason) {
        stop_reason = context_limited ? "context_full" : "max_tokens";
    }
    if (!consumer_closed && !callback_buffer.empty()) {
        if (!parser.update(
                env, callback, callbacks,
                callback_buffer.data(), callback_buffer.size(), true, failure
            )) {
            if (failure.empty()) failure = "Stream consumer closed";
            consumer_closed = true;
        }
    }
    if (failure.empty() && !utf8_pending.empty()) {
        failure = "Generated incomplete UTF-8 output";
    }
    if (failure.empty() && !consumer_closed && stop_reason &&
        std::strcmp(stop_reason, "cancelled") != 0 &&
        !parser.finish(env, callback, callbacks, failure)) {
        if (failure.empty()) failure = "Stream consumer closed";
        consumer_closed = true;
    }
    const auto decode_finished = std::chrono::steady_clock::now();
    const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - decode_started
    ).count();
    const auto request_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        decode_finished - request_started
    ).count();
    const double tokens_per_second = decode_ms > 0
        ? generated * 1000.0 / static_cast<double>(decode_ms)
        : 0.0;
    LOGD("Multimodal decode: output_tokens=%d, duration_ms=%lld, tokens_per_second=%.2f, terminal=%s",
         generated, (long long)decode_ms, tokens_per_second,
         failure.empty() ? stop_reason : "error");
    LOGD("Multimodal request: input_tokens=%lld, output_tokens=%d, total_ms=%lld",
         (long long)n_past, generated, (long long)request_ms);
    common_sampler_free(smpl);
    const int32_t input_tokens = static_cast<int32_t>(n_past);
    clear_text_cache(handle);
    if (!failure.empty()) {
        return report_error(
            env, callback, callbacks, failure.c_str(), input_tokens, generated
        );
    }
    return report_done(
        env, callback, callbacks, stop_reason, input_tokens, generated
    );
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);

    if (handle->mtmd_ctx) mtmd_free(handle->mtmd_ctx);
    handle->chat_templates.reset();
    if (handle->ctx)   llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);

    LOGD("Chat model freed");
    delete handle;
}

JNIEXPORT void JNICALL
Java_com_newoether_agora_api_LlamaChatEngine_nativeChatCancel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle_ptr) {

    if (!handle_ptr) return;
    ChatHandle * handle = reinterpret_cast<ChatHandle *>(handle_ptr);
    handle->cancelled.store(true, std::memory_order_relaxed);
    LOGD("Cancellation requested");
}

} // extern "C"
