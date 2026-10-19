package com.newoether.agora.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AskUserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Mantiene una pregunta ask_user sin responder accionable mientras la interaction bar no está en
 * pantalla.
 *
 * El bar vive en la pantalla de chat, así que una pregunta hecha con Agora en background, o con
 * otra pantalla abierta, esperaría invisiblemente y el tool call jamás retornaría. Esto refleja
 * la pregunta en espera más vieja en una notificación y la limpia en cuanto la pantalla de chat
 * se muestra o no queda nada esperando.
 *
 * Una notificación sostiene a lo más tres acciones, así que las opciones aparecen como acciones
 * solo para una pregunta corta de elección única. Todo lo demás se responde en la app, que la
 * notificación abre.
 */
object AskUserNotifier {

    const val ACTION_ANSWER = "com.newoether.agora.action.ASK_USER_ANSWER"
    const val ACTION_SKIP = "com.newoether.agora.action.ASK_USER_SKIP"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OPTION_INDEX = "option_index"
    const val EXTRA_SESSION_ID = "ask_user_session"

    private const val TAG = "AskUserNotifier"
    private const val CHANNEL_ID = "ask_user"
    private const val NOTIFICATION_ID = 7302
    // Dos opciones más Skip llenan las tres acciones que una notificación puede mostrar.
    private const val MAX_OPTION_ACTIONS = 2

    /** Observa la cola de preguntas process-wide por el resto de este proceso. */
    fun start(scope: CoroutineScope, context: Context, controller: AskUserController) {
        val appContext = context.applicationContext
        scope.launch {
            combine(
                controller.requests,
                AppForegroundTracker.foreground,
                AppForegroundTracker.chatPresented,
            ) { requests, foreground, chatPresented ->
                requests.firstOrNull()?.takeIf { !foreground || !chatPresented }
            }.distinctUntilChanged().collect { request ->
                if (request == null) {
                    cancel(appContext)
                } else {
                    showQuestion(appContext, request, controller.notificationSessionId)
                }
            }
        }
    }

    private fun showQuestion(
        context: Context,
        request: AskUserController.Request,
        sessionId: String,
    ) {
        createChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ask_user_title))
            .setContentText(request.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.question))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
        if (!request.allowMultiple && request.options.size <= MAX_OPTION_ACTIONS) {
            request.options.forEachIndexed { index, option ->
                builder.addAction(0, option, answerIntent(context, request.id, index, sessionId))
            }
        }
        builder.addAction(
            0,
            context.getString(R.string.ask_user_skip),
            skipIntent(context, request.id, sessionId),
        )
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, builder.build())
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to post ask_user notification", e)
        }
    }

    private fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to clear ask_user notification", e)
        }
    }

    private fun answerIntent(
        context: Context,
        requestId: Long,
        optionIndex: Int,
        sessionId: String,
    ): PendingIntent = actionIntent(
        context = context,
        requestId = requestId,
        action = ACTION_ANSWER,
        sessionId = sessionId,
        optionIndex = optionIndex,
    )

    private fun skipIntent(context: Context, requestId: Long, sessionId: String): PendingIntent =
        actionIntent(
            context = context,
            requestId = requestId,
            action = ACTION_SKIP,
            sessionId = sessionId,
            optionIndex = -1,
        )

    private fun actionIntent(
        context: Context,
        requestId: Long,
        action: String,
        sessionId: String,
        optionIndex: Int,
    ): PendingIntent {
        // El data uri mantiene un PendingIntent por request y opción, así una acción sobrante de
        // una pregunta ya respondida jamás lleva la respuesta de una pregunta más nueva.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(action, null, context, AskUserReceiver::class.java)
                .setData(
                    android.net.Uri.parse("agora-ask-user://$sessionId/$requestId/$optionIndex")
                )
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_INDEX, optionIndex),
            flags,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val launchFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            launchFlags,
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ask_user_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ask_user_notification_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
