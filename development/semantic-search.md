# Semantic Search Architecture Contract

Status: authoritative development contract, 2026-08-14.

This document is required context for changes to embedding-cache reads, semantic conversation search,
RAG ranking, or the search eligibility query. Semantic search must remain bounded by one database
page plus the requested top candidates; corpus growth must not translate into Android heap growth.

## 1. Observable behavior

- Query embedding generation, model selection, API-key resolution, and user-visible tool behavior
  remain owned by the existing RAG/provider path.
- Searchable sources exclude Task conversations, non-USER/non-MODEL rows, blank or short source
  text, and synthetic tool/result/Compact rows.
- Similarity is cosine similarity. Candidates must be strictly above the configured RAG threshold,
  ordered by descending score, and limited to the requested count.
- Stable embedding row id is the deterministic tie-breaker for equal scores.
- Invalid dimensions, malformed byte lengths, and non-finite scores are skipped without aborting
  the remaining corpus and without logging message content.

## 2. Bounded data flow

1. ChatDao reads a minimal projection containing only embedding row id, message id, embedding
   bytes, and declared dimension.
2. The DAO uses stable keyset pagination (id > afterId, ORDER BY id, bounded LIMIT). It never
   materializes the complete model corpus for semantic search.
3. The selector scores one page at a time directly from the durable BIG_ENDIAN bytes and does not
   allocate a decoded FloatArray for every row.
4. A bounded worst-first top-K heap retains at most the requested result count across all pages.
5. Only the final bounded message-id set is expanded into complete searchable MessageEntity rows.
6. The final expansion revalidates search visibility and minimum source length before returning.

Peak application memory is therefore proportional to one configured page, the cached query vector, the
bounded top-K heap, and the final bounded message set. It is not proportional to embedding-row
count or total cached text.

## 3. Ownership

| Owner | Responsibility | Prohibited responsibility |
|---|---|---|
| ChatDao | Eligibility join, minimal projection, deterministic keyset page. | Full-corpus semantic list or score/ranking policy. |
| ConversationRepository | Pass through the bounded page contract. | Reassembling pages into one collection. |
| BoundedSemanticEmbeddingSelector | Vector validation, page-by-page scoring, strict threshold, bounded top-K, stable ranking. | Room access, Provider calls, message visibility policy, or cache mutation. |
| RagToolProvider | Query embedding, selector orchestration, final bounded message expansion, tool result projection. | Full-corpus materialization or a second ranking implementation. |

`MAX_EMBEDDING_TEXT_LENGTH` (`Constants.kt`, currently 8000) is load-bearing in `semanticSourceFingerprint`
(`SemanticIndexLedger.kt`) and in the generation-time text truncation (`EmbeddingCacheWorker.kt`). Changing
it invalidates every stored source fingerprint and silently re-truncates different text windows, forcing a
full reconcile of all models. Treat it as immutable post-release; any change requires a deliberate schema
revision plus a documented reindex, never a silent constant bump.

## 4. Failure and concurrency behavior

- A malformed cache row cannot fail the whole search.
- A page must be strictly ordered and advance the keyset; a broken loader fails instead of looping.
- Message deletion or visibility changes between scoring and final expansion may only remove a
  candidate. They must not expose a hidden row.
- Search is read-only. It must not delete/rebuild cache rows, increase the heap limit, or retry the
  complete scan as a correctness mechanism.
- Logs may contain aggregate row counts, invalid-row counts, dimensions, and scores, but no source
  message text, embedding bytes, credentials, or conversation content.

## 5. Cache-count presentation

Conversation Search cache counts are retained asynchronous presentation data. `RagManager` must not
start an aggregate refresh from its constructor. A refresh begins only after the conversation list
has been published or the Settings page explicitly requests it, and it must never delay list
publication. Overlapping requests are coalesced; if the configured model set changes during an
active refresh, one refresh for the latest set runs afterward. One bounded DAO aggregate returns
cached counts grouped by configured model id while the indexable-message total is read independently.
No query returns message text or embedding blobs, and page entry must not issue N+1 model counts.

Each model keeps the last complete count snapshot. Before the first snapshot, an active request shows
loading and an initial failure shows failure plus Retry; neither state may fabricate zero, uncached,
or an available Cache/Re-cache action. A refresh failure after a successful snapshot retains that
snapshot and the ledger-owned action. The presentation read never acquires the model write mutex, so
an active or failed worker cannot strand Conversation Search in Loading. Status and action changes use
fixed slots with a 250 ms crossfade. Aggregate counts may supply exact numeric status and
uncached-reminder copy, but they never establish freshness or choose Cache versus Re-cache. Those
decisions use only the semantic ledger.

No timer, polling loop, periodic Worker, or continuously invalidating Room Flow is introduced for
this status. Failures log only aggregate diagnostics. Semantic ranking remains governed by the
bounded search path above; count presentation cannot materialize, decode, rank, delete, or rebuild
embedding rows.

## 6. Automatic cache backfill and reminder

`Auto Cache` remains enabled by default and owns incremental indexing of newly persisted eligible
messages. Semantic cache completeness is maintained durably rather than rediscovered by scanning the
message and embedding tables at every launch.

One lightweight ledger row per Embedding model stores whether that model is complete, has exact
pending work, or requires bounded reconciliation/initial backfill. Searchable-message admission,
text or eligibility changes, deletion, conversation deletion, fork/import, embedding success, and
embedding invalidation update the semantic ledger and exact work identity in the same durable
transaction as the owning mutation. Each work item is uniquely identified by model and message and
includes the current source fingerprint or revision, so duplicate events coalesce and an embedding
for older text cannot satisfy current content. Inactive models may retain one `needsReconcile` state
instead of multiplying per-message work; activating a new or stale model admits one bounded keyset
reconciliation. Database migration marks affected models for reconciliation without scanning message
content during application entry.

Interactive App startup first publishes the conversation-list projection. Only after that list is
visibly available may `RagManager` admit the active model by reading its single ledger row. This O(1)
check must not execute aggregate counts, enumerate conversations or owners, load message text or
embedding blobs, or instantiate conversation runtime state. Model switching, enabling Auto Cache,
new model admission, manual Cache, and manual Re-cache use the same ledger admission path. Re-cache
invalidates that model under the shared model mutex before scheduling durable work.

`EmbeddingCacheWorker` is the only cache embedding generator. `RagManager` owns ledger admission,
unique durable scheduling, worker observation, model lifecycle, reminder delivery, and retained
aggregate presentation; it must not hold an in-process cache loop or invoke an embedding engine.
When the ledger is not current and Auto Cache is enabled, exactly one per-model unique worker may run.
A wakeup that arrives while the current worker is running appends one `APPEND_OR_REPLACE` follower so
newly admitted work is not lost behind a stale unique-work KEEP decision. Scheduling and worker-state
observation never hold the model write mutex.

The worker consumes bounded exact-work pages or bounded full-reconcile pages, limits embedding batch
size, yields between pages, and commits only when both the database source fingerprint and durable
work revision still match its admitted candidate. Failed or superseded items remain durable work for
a later pass. Worker activity is observed from unique WorkManager state; completion may refresh
presentation but aggregate equality cannot mark the ledger current.

The worker emits no uncached, caching, success, completion, partial-failure, or setup-failure
Snackbar. Manual cache and recache actions retain their existing feedback. Deleting a model cancels
its unique work and performs model deletion under the same process-wide model mutex; the mutex entry
is retained so existing and future waiters cannot become concurrent writers.

`Show Uncached Notification` is a separate default-on portable setting. It is consulted only while
Auto Cache is disabled. After the conversation list is visible, the same one-row ledger check may
request a background aggregate refresh for exact reminder copy. The reminder may be emitted only by
a refresh that includes the target model, and it must not treat a count as freshness evidence.
Disabling the setting leaves work pending and emits no reminder. The Settings row is placed directly
below Auto Cache and is not shown while Auto Cache is enabled.

## 7. Required verification

Focused verification must cover aggregate count mapping, configured models with no rows, coalesced
refresh, retained-snapshot behavior, the model-leading migration/index, and absence of page-owned N+1
count loops. Semantic-search verification must still cover multiple pages, ranking across page boundaries, strict threshold
exclusion, bounded retained candidates, deterministic equal-score ordering, empty results,
dimension/byte-shape corruption, non-finite vectors, and a source/DAO contract preventing the
unbounded full-list hot path from returning.
