package desu.inugram.jvmfixture

open class JvmClassFixture(val number: Long, val label: String) {
    open fun getText(): CharSequence = label
}

abstract class JvmAbstractClassFixture {
    abstract fun getText(): String
}
