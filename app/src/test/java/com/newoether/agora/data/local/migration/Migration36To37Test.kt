package com.newoether.agora.data.local.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration36To37Test {
    @Test
    fun migrationAddsOnlyNullableRequestKindColumnToRuns() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(capture(statements)) } just Runs

        MIGRATION_36_37.migrate(database)

        assertEquals(36, MIGRATION_36_37.startVersion)
        assertEquals(37, MIGRATION_36_37.endVersion)
        assertEquals(listOf("ALTER TABLE runs ADD COLUMN requestKind TEXT"), statements)
    }

    @Test
    fun generatedRoomSchemaMatchesMigrationAddsNullableRequestKindAndPreservesOtherEntities() {
        val oldDatabase = Json.parseToJsonElement(locateSchema(36).readText()).jsonObject.getValue("database").jsonObject
        val newDatabase = Json.parseToJsonElement(locateSchema(37).readText()).jsonObject.getValue("database").jsonObject

        assertEquals(37, newDatabase.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(oldDatabase.getValue("entities").jsonArray.size, newDatabase.getValue("entities").jsonArray.size)

        val newEntities = newDatabase.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }
        val oldEntities = oldDatabase.getValue("entities").jsonArray.associateBy {
            it.jsonObject.getValue("tableName").jsonPrimitive.content
        }

        // runs: requestKind added as nullable TEXT (Room omits notNull when false); every
        // pre-existing column is untouched.
        val newRuns = newEntities.getValue("runs").jsonObject
        val newRunsFields = newRuns.getValue("fields").jsonArray
        val requestKind = newRunsFields.single {
            it.jsonObject.getValue("columnName").jsonPrimitive.content == "requestKind"
        }.jsonObject
        assertTrue("notNull" !in requestKind)
        assertEquals("TEXT", requestKind.getValue("affinity").jsonPrimitive.content)
        val oldRunsColumns = oldEntities.getValue("runs").jsonObject.getValue("fields").jsonArray.map {
            it.jsonObject.getValue("columnName").jsonPrimitive.content
        }
        val newRunsColumns = newRunsFields.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
        assertTrue(newRunsColumns.containsAll(oldRunsColumns))

        // Every other entity is byte-identical between v36 and v37.
        oldEntities.forEach { (name, oldEntity) ->
            if (name == "runs") return@forEach
            assertEquals(oldEntity.jsonObject["fields"], newEntities.getValue(name).jsonObject["fields"])
            assertEquals(oldEntity.jsonObject["foreignKeys"], newEntities.getValue(name).jsonObject["foreignKeys"])
            assertEquals(oldEntity.jsonObject["indices"], newEntities.getValue(name).jsonObject["indices"])
        }
    }

    private fun locateSchema(version: Int): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/schemas/com.newoether.agora.data.local.ChatDatabase/$version.json"),
                File(directory, "schemas/com.newoether.agora.data.local.ChatDatabase/$version.json"),
            ).firstOrNull(File::isFile)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate Room schema $version")
    }
}
