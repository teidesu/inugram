package desu.inugram.helpers.plugins

import java.lang.reflect.Modifier
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [Plugin] is written from the ui thread and read from `globalQueue` (and the other way round), so
 * every mutable field of it crosses threads. There is no lock anywhere: publication is the whole
 * mechanism, which makes a missing `@Volatile` invisible until a user reports rows that are
 * sometimes not there.
 */
class PluginStateTest {
    private fun mutableFields() = Plugin::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }

    @Test
    fun `every field of a plugin that crosses threads is published`() {
        val unpublished = mutableFields()
            .filter { !Modifier.isVolatile(it.modifiers) }
            .map { it.name }
        assertTrue(
            unpublished.isEmpty(),
            "not @Volatile: $unpublished - PluginActions.render reads `engine` on the ui thread " +
                "before it posts, so a plugin just enabled is missing from the menu being opened",
        )
    }

    @Test
    fun `a new mutable field has to say which thread owns it`() {
        val known = setOf("parsed", "source", "manifest", "enabled", "failure", "engine", "settingsPageId")
        val unknown = mutableFields().map { it.name } - known
        assertTrue(unknown.isEmpty(), "new mutable Plugin fields: $unknown")
    }
}
