package desu.inugram.helpers.plugins

interface SessionResource {
    /** on the plugin queue, before the engine closes */
    fun detach(session: PluginSession)
}
