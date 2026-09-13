package desu.inugram.helpers.plugins

/** host state a running plugin holds; [PluginManager]'s teardown detaches every one of these */
interface SessionResource {
    /** on the plugin queue, as the session stops and before its engine closes */
    fun detach(session: PluginSession)
}
