package desu.inugram.helpers.plugins.platform

import androidx.annotation.Keep
import dalvik.system.InMemoryDexClassLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

@Keep
internal object PluginJvmClass {
    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /**
     * Generates names for unnamed classes. Loaded DEX cannot be unloaded, so every class needs
     * a fresh name. The install ID separates packages for different installs of the same plugin.
     * Explicit names in this package are still allowed but receive no collision protection.
     */
    const val GENERATED_PACKAGE = "inu.plugins"

    private val names = SecureRandom()

    private fun generateName(installId: String): String {
        val suffix = ByteArray(8).also(names::nextBytes).joinToString("") { "%02x".format(it) }
        return "$GENERATED_PACKAGE.i$installId.DefinedClass$suffix"
    }

    private class Invocation(var depth: Int = 0, val deadline: Long = System.nanoTime() + 250_000_000L)
    private val invocation = ThreadLocal<Invocation>()
    class Definition(val type: Class<*>, val targets: List<MethodTarget>) {
        fun close() { targets.forEach { it.close() } }
    }

    private inline fun <T> runBudgeted(block: () -> T): T {
        val call = invocation.get() ?: Invocation().also(invocation::set)
        check(call.depth < 64 && System.nanoTime() < call.deadline) { "defineClass: invocation budget exceeded" }
        call.depth++
        try {
            return block()
        } finally {
            if (--call.depth == 0) invocation.remove()
        }
    }

    private fun convert(value: Any?, type: Class<*>, what: String): Any? {
        if (type.isInstance(value)) return value
        return requireNotNull(PluginJvm.convertArguments(arrayOf(type), listOf(value))) {
            "defineClass: $what does not match ${type.name}"
        }[0]
    }

    sealed class SuperSource {
        class Fixed(val items: List<Pair<Int?, Any?>>) : SuperSource()
        class Computed(val callback: (Class<*>, Any?, Array<Any?>) -> Any?) : SuperSource()
    }

    @Keep
    class MethodTarget(
        private val returnType: Class<*>,
        callback: ((Class<*>, Any?, Array<Any?>) -> Any?)?,
        private val superTypes: Array<Class<*>>,
        superSource: SuperSource?,
    ) {
        @Volatile private var callback = callback
        @Volatile private var superSource = superSource
        @Volatile private var closed = false
        lateinit var owner: Class<*>

        fun invoke(self: Any?, args: Array<Any?>): Any? {
            if (closed && returnType == Void.TYPE) return null
            check(!closed) { "defineClass: plugin has unloaded" }
            return runBudgeted {
                val result = callback?.invoke(owner, self ?: owner, args)
                if (returnType == Void.TYPE) null else convert(result, returnType, "result")
            }
        }

        fun getSuperArguments(args: Array<Any?>): Array<Any?> {
            val source = superSource
            check(!closed && source != null) { "defineClass: plugin has unloaded" }
            val values = when (source) {
                is SuperSource.Fixed -> source.items.map { (index, value) -> if (index != null) args[index] else value }
                is SuperSource.Computed -> when (val result = runBudgeted { source.callback(owner, null, args) }) {
                    is Array<*> -> result.toList()
                    else -> error("defineClass: super must return an array")
                }
            }
            check(values.size == superTypes.size) { "defineClass: super returned ${values.size} arguments, the super constructor takes ${superTypes.size}" }
            return Array(values.size) { convert(values[it], superTypes[it], "super argument") }
        }

        fun close() {
            closed = true
            callback = null
            superSource = null
        }
    }

    private class MethodSpec(
        val name: String,
        val params: Array<Class<*>>,
        val returns: Class<*>,
        val isStatic: Boolean,
        val constructor: Constructor<*>?,
        val sources: List<Pair<Int?, Any?>>,
        val superBody: Any?,
        val body: Any?,
        val resultType: Class<*> = returns,
    )

    fun prepare(
        definition: String,
        values: List<Any?>,
        resolve: (String) -> Class<*>,
        parent: ClassLoader,
        installId: String,
        dispatch: (Int, Any?, Array<Any?>) -> Any?,
    ): Prepared {
        require(definition.toByteArray(Charsets.UTF_8).size <= PluginJvm.VALUE_LIMIT_BYTES) { "class definition exceeds 1 MB" }
        val spec = JSONObject(definition)
        val name = if (spec.isNull("name")) generateName(installId) else spec.getString("name").replace('/', '.')
        require(name.split('.').all { it.matches(IDENTIFIER) } && !name.startsWith("java.") && !name.startsWith("android.") && !PluginJvm.isEnginePackage(name)) { "invalid class name" }
        require(runCatching { resolve(name) }.exceptionOrNull() is ClassNotFoundException) { "class already exists: $name" }
        fun capture(index: Int): Any? {
            require(index in values.indices) { "invalid class capture" }
            return values[index]
        }
        val superclass = if (spec.isNull("superclass")) Any::class.java else capture(spec.getInt("superclass")) as? Class<*>
        require(superclass != null && Modifier.isPublic(superclass.modifiers) && !Modifier.isFinal(superclass.modifiers) && !superclass.isInterface && !superclass.isArray && !superclass.isPrimitive) { "superclass must be a public, non-final class" }
        val interfaceSpecs = spec.getJSONArray("interfaces")
        require(interfaceSpecs.length() <= 64) { "at most 64 interfaces" }
        val interfaces = List(interfaceSpecs.length()) {
            val type = capture(interfaceSpecs.getInt(it)) as? Class<*>
            require(type != null && type.isInterface && Modifier.isPublic(type.modifiers)) { "expected a public interface" }
            type
        }
        require(interfaces.distinct().size == interfaces.size) { "duplicate interface" }
        val fields = spec.getJSONArray("fields")
        val methods = spec.getJSONArray("methods")
        require(fields.length() <= 256 && methods.length() <= 256) { "at most 256 fields and 256 methods per class" }
        fun getType(text: String, allowVoid: Boolean = false): Class<*> {
            val type = when (text) {
                "void", "V" -> Void.TYPE
                "boolean", "Z" -> Boolean::class.javaPrimitiveType!!
                "byte", "B" -> Byte::class.javaPrimitiveType!!
                "char", "C" -> Char::class.javaPrimitiveType!!
                "short", "S" -> Short::class.javaPrimitiveType!!
                "int", "I" -> Int::class.javaPrimitiveType!!
                "long", "J" -> Long::class.javaPrimitiveType!!
                "float", "F" -> Float::class.javaPrimitiveType!!
                "double", "D" -> Double::class.javaPrimitiveType!!
                else -> when {
                    text.endsWith("[]") -> java.lang.reflect.Array.newInstance(getType(text.dropLast(2)), 0).javaClass
                    text.startsWith("[") -> resolve(text.replace('/', '.'))
                    text.startsWith("L") && text.endsWith(";") -> resolve(text.substring(1, text.length - 1).replace('/', '.'))
                    else -> resolve(text.replace('/', '.'))
                }
            }
            require(allowVoid || type != Void.TYPE) { "void is not a field or parameter type" }
            var component = type
            while (component.isArray) component = component.componentType!!
            require(component.isPrimitive || Modifier.isPublic(component.modifiers)) { "type must be public: ${type.name}" }
            return type
        }
        fun getParams(array: JSONArray): Array<Class<*>> {
            require(array.length() <= 64) { "at most 64 parameters" }
            return Array(array.length()) { getType(array.getString(it)) }
        }
        fun readBody(bodySpec: JSONArray): Any {
            val value = capture(bodySpec.getInt(1))
            return when (bodySpec.getString(0)) {
                "js" -> { require(value is Long && value in 1..Int.MAX_VALUE.toLong()) { "invalid JS callback" }; value.toInt() }
                "routine" -> { require(value is PluginJvmRoutine) { "body must be an inu.jvm.routine" }; value }
                else -> error("invalid method body")
            }
        }
        fun checkName(value: String) {
            require(value.matches(IDENTIFIER) && !value.startsWith("inu$")) { "invalid or reserved member name: $value" }
        }
        val fieldNames = HashSet<String>()
        val fieldData = Array(fields.length()) {
            val field = fields.getJSONArray(it)
            val fieldName = field.getString(0)
            checkName(fieldName)
            require(fieldNames.add(fieldName)) { "duplicate field: $fieldName" }
            arrayOf(fieldName, PluginJvm.descriptorOf(getType(field.getString(1))), if (field.getBoolean(2)) "1" else "0")
        }
        val inherited = ArrayList<Method>()
        var current: Class<*>? = superclass
        while (current != null) {
            inherited.addAll(current.declaredMethods.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) })
            current = current.superclass
        }
        inherited.addAll(superclass.methods)
        interfaces.forEach { inherited.addAll(it.methods) }
        val signatures = HashSet<String>()
        val methodSpecs = List(methods.length()) {
            val method = methods.getJSONObject(it)
            val methodName = method.getString("name")
            val isConstructor = method.getBoolean("constructor")
            val isStatic = method.getBoolean("static")
            require(!isConstructor || methodName == "<init>" && !isStatic) { "invalid constructor" }
            if (!isConstructor) checkName(methodName)
            val candidates = inherited.filter { it.name == methodName && !it.isBridge && !it.isSynthetic }
            val params = if (!method.isNull("params")) getParams(method.getJSONArray("params")) else {
                val prototypes = candidates.map { it.parameterTypes.toList() }.distinct()
                require(prototypes.size <= 1) { "overloaded method $methodName needs explicit params" }
                (prototypes.singleOrNull() ?: emptyList()).toTypedArray()
            }
            val matches = candidates.filter { it.parameterTypes.contentEquals(params) }
            val returns = if (!method.isNull("returns")) getType(method.getString("returns"), true) else {
                val types = matches.map { it.returnType }.distinct()
                types.firstOrNull { candidate -> types.all { it.isAssignableFrom(candidate) } } ?: Void.TYPE
            }
            require(!isConstructor || returns == Void.TYPE) { "constructors return void" }
            for (base in matches) {
                require(!Modifier.isFinal(base.modifiers)) { "cannot override final method $methodName" }
                require(Modifier.isStatic(base.modifiers) == isStatic) { "static/instance mismatch for $methodName" }
                require(base.returnType == returns || !base.returnType.isPrimitive && base.returnType.isAssignableFrom(returns)) { "incompatible return type for $methodName" }
            }
            val signature = methodName + params.joinToString(prefix = "(", postfix = ")") { PluginJvm.descriptorOf(it) }
            require(signatures.add(signature)) { "duplicate method: $signature" }
            val body = if (method.isNull("body")) null else readBody(method.getJSONArray("body"))
            require(isConstructor || body != null) { "method needs a body" }
            val superBody = if (!isConstructor || method.isNull("superBody")) null else readBody(method.getJSONArray("superBody"))
            val sources = if (!isConstructor) emptyList() else {
                val args = method.getJSONArray("super")
                require(args.length() <= 64) { "at most 64 super arguments" }
                List(args.length()) {
                    val arg = args.getJSONObject(it)
                    if (arg.has("arg")) {
                        val index = arg.getInt("arg")
                        require(index in params.indices) { "super argument index out of range" }
                        index to null
                    } else null to capture(arg.getInt("value"))
                }
            }
            val accessibleSuperConstructors = superclass.declaredConstructors.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            val superConstructor = if (!isConstructor) null else if (superBody != null) {
                if (method.isNull("superParams")) {
                    require(accessibleSuperConstructors.size == 1) { "a super function needs superParams unless the superclass has exactly one constructor" }
                    accessibleSuperConstructors.single()
                } else {
                    val superParams = getParams(method.getJSONArray("superParams"))
                    requireNotNull(accessibleSuperConstructors.find { it.parameterTypes.contentEquals(superParams) }) {
                        "the superclass has no accessible constructor taking (${superParams.joinToString { it.name }})"
                    }
                }
            } else {
                val options = accessibleSuperConstructors.filter { constructor ->
                    constructor.parameterCount == sources.size && sources.indices.all { index ->
                        val (arg, value) = sources[index]
                        val type = constructor.parameterTypes[index]
                        if (arg == null) PluginJvm.convertArguments(arrayOf(type), listOf(value)) != null
                        else canAssign(params[arg], type)
                    }
                }
                val best = options.filter { candidate -> options.none { other -> other !== candidate && candidate.parameterTypes.indices.all { canAssign(other.parameterTypes[it], candidate.parameterTypes[it]) } && !candidate.parameterTypes.contentEquals(other.parameterTypes) } }
                require(best.size == 1) { "super constructor is missing or ambiguous" }
                best.single()
            }
            MethodSpec(methodName, params, returns, isStatic, superConstructor, sources, superBody, body)
        }.toMutableList()
        val effectiveInherited = inherited.distinctBy { it.name + PluginJvm.descriptorOf(it.returnType) + it.parameterTypes.joinToString { PluginJvm.descriptorOf(it) } }
        for (method in effectiveInherited.filter { Modifier.isAbstract(it.modifiers) }) {
            val signature = method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { PluginJvm.descriptorOf(it) }
            require(signature in signatures || effectiveInherited.any { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes) && method.returnType.isAssignableFrom(it.returnType) }) { "abstract method needs implementation: $signature" }
        }
        for (method in methodSpecs.toList().filter { it.constructor == null && !it.isStatic }) {
            val bridgeReturns = inherited.filter { it.name == method.name && it.parameterTypes.contentEquals(method.params) && it.returnType != method.returns && it.returnType.isAssignableFrom(method.returns) }.map { it.returnType }.distinct()
            for (returns in bridgeReturns) methodSpecs.add(MethodSpec(method.name, method.params, returns, false, null, emptyList(), null, method.body, method.returns))
        }
        require(methodSpecs.size <= 256) { "at most 256 methods including covariant bridges" }
        fun createCallback(body: Any?): ((Class<*>, Any?, Array<Any?>) -> Any?)? = when (body) {
            is Int -> { _, self, args -> dispatch(body, self, args) }
            is PluginJvmRoutine -> { owner, self, args -> body.execute(null, self, args, owner) }
            else -> null
        }
        val targets = methodSpecs.map { method ->
            val superSource = when {
                method.constructor == null -> null
                method.superBody != null -> SuperSource.Computed(createCallback(method.superBody)!!)
                else -> SuperSource.Fixed(method.sources)
            }
            MethodTarget(method.resultType, createCallback(method.body), method.constructor?.parameterTypes ?: emptyArray(), superSource)
        }
        val methodData = methodSpecs.map { method ->
            val superParams = method.constructor?.parameterTypes ?: emptyArray()
            (listOf(method.name, PluginJvm.descriptorOf(method.returns), if (method.isStatic) "1" else "0", method.params.size.toString()) + method.params.map(PluginJvm::descriptorOf) + superParams.size.toString() + superParams.map(PluginJvm::descriptorOf)).toTypedArray()
        }.toTypedArray()
        return Prepared(name, PluginJvm.descriptorOf(superclass), interfaces.map(PluginJvm::descriptorOf).toTypedArray(), fieldData, methodData, parent, targets)
    }

    class Prepared(
        val name: String,
        private val superclass: String,
        private val interfaces: Array<String>,
        private val fields: Array<Array<String>>,
        private val methods: Array<Array<String>>,
        private val parent: ClassLoader,
        private val targets: List<MethodTarget>,
    ) {
        fun getMetadata(ticket: Long): String = JSONObject().apply {
            put("ticket", ticket.toString())
            put("name", name)
            put("superclass", superclass)
            put("interfaces", JSONArray(interfaces.toList()))
            put("fields", JSONArray(fields.map { JSONArray(it.toList()) }))
            put("methods", JSONArray(methods.map { JSONArray(it.toList()) }))
        }.toString()

        fun load(bytes: ByteArray): Definition {
            try {
                require(bytes.isNotEmpty() && bytes.size <= PluginJvm.DEX_LIMIT_BYTES) { "invalid class DEX size" }
                val type = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), parent).loadClass(name)
                targets.forEachIndexed { index, target ->
                    target.owner = type
                    type.getDeclaredField("inu\$dispatch$index").set(null, target)
                }
                return Definition(type, targets)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun close() { targets.forEach { it.close() } }
    }

    private fun canAssign(from: Class<*>, to: Class<*>): Boolean {
        if (from == to || to.isAssignableFrom(from)) return true
        val widening: List<Class<*>?> = when (from) {
            Byte::class.javaPrimitiveType -> listOf(Short::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            Short::class.javaPrimitiveType, Char::class.javaPrimitiveType -> listOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            Int::class.javaPrimitiveType -> listOf(Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            Long::class.javaPrimitiveType -> listOf(Float::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            Float::class.javaPrimitiveType -> listOf(Double::class.javaPrimitiveType)
            else -> emptyList()
        }
        return to in widening
    }
}
