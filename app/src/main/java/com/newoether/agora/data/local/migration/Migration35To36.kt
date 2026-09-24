package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_35_36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Email messages table (composite PK: accountId + uid — email is multi-account)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS email_messages (
                accountId TEXT NOT NULL,
                uid INTEGER NOT NULL,
                fromAddress TEXT NOT NULL,
                toAddress TEXT NOT NULL,
                subject TEXT NOT NULL,
                dateEpochMs INTEGER NOT NULL,
                preview TEXT NOT NULL,
                body TEXT NOT NULL,
                messageId TEXT,
                isRead INTEGER NOT NULL,
                listUnsubscribe TEXT,
                listUnsubscribePost TEXT,
                PRIMARY KEY (accountId, uid)
            )
        """)

        // Email sync state table (one row per account — no global singleton)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS email_sync_state (
                accountId TEXT PRIMARY KEY NOT NULL,
                lastSeenUid INTEGER NOT NULL,
                lastSyncEpochMs INTEGER NOT NULL,
                lastAttemptEpochMs INTEGER NOT NULL,
                unreadCount INTEGER NOT NULL,
                lastError TEXT
            )
        """)

        // Email pending queue (composite PK mirrors sms_pending's capped FIFO role)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS email_pending (
                accountId TEXT NOT NULL,
                uid INTEGER NOT NULL,
                fromAddress TEXT NOT NULL,
                subject TEXT NOT NULL,
                dateEpochMs INTEGER NOT NULL,
                preview TEXT NOT NULL,
                isRead INTEGER NOT NULL,
                PRIMARY KEY (accountId, uid)
            )
        """)

        // Email drafts table (approval banner staging, mirroring sms_drafts)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS email_drafts (
                id TEXT PRIMARY KEY NOT NULL,
                accountId TEXT NOT NULL,
                toAddress TEXT NOT NULL,
                subject TEXT NOT NULL,
                body TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                inReplyToMessageId TEXT,
                status TEXT NOT NULL,
                lastError TEXT
            )
        """)
    }
}
