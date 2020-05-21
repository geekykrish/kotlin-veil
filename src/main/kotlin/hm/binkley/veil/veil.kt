package hm.binkley.veil

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy.newProxyInstance
import java.util.Objects

fun main() {
    val ds = FakeDataSource()
    val bobs = veil<Bob>(::RealBob, ds, ds.fetch("SELECT *"), "x")

    bobs.forEach {
        println("VEILED: Bob{x=${it.x}, y=${it.y}}")
        println("REAL: $it")
    }
}

interface DataSource {
    fun fetch(q: String, vararg a: Any?): Sequence<Map<String, Any?>>
}

class FakeDataSource : DataSource {
    override fun fetch(
        q: String, vararg a: Any?
    ): Sequence<Map<String, Any?>> {
        println("FETCHING${a.contentToString()} -> $q")
        return when (q) {
            "SELECT *" -> sequenceOf(
                mapOf(
                    "id" to 1,
                    "x" to 2,
                    "y" to "apple"
                ),
                mapOf(
                    "id" to 2,
                    "x" to 3,
                    "y" to "banana"
                )
            )
            "SELECT x WHERE ID = :id" -> when (a[0]) {
                1 -> sequenceOf(mapOf("x" to 2))
                2 -> sequenceOf(mapOf("x" to 3))
                else -> sequenceOf(mapOf())
            }
            "SELECT y WHERE ID = :id" -> when (a[0]) {
                1 -> sequenceOf(mapOf("y" to "apple"))
                2 -> sequenceOf(mapOf("y" to "banana"))
                else -> sequenceOf(mapOf())
            }
            else -> error("Unknown: $q")
        }
    }
}

private fun prop(methodName: String) =
    if (methodName.startsWith("get"))
        methodName.removePrefix("get").decapitalize()
    else methodName

class Veiler(
    private val real: Any,
    private val data: Map<String, Any?>,
    vararg _keys: String
) : InvocationHandler {
    private val keys = _keys

    init {
        println("HANDLER -> ${keys.contentToString()}$data")
    }

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?
    ): Any? {
        val key = prop(method.name)
        if (key in keys) {
            println("VEILING -> ${method.name}=${data[key]}")
            return data[key]
        }

        println("CALLING ON ${real::class.simpleName} -> ${method.name}")
        return if (args == null) method(real)
        else method(real, *args)
    }
}

inline fun <reified T> veil(
    crossinline real: (DataSource, Int) -> T,
    ds: DataSource,
    data: Sequence<Map<String, Any?>>,
    vararg keys: String
) = data.map {
    newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        Veiler(real(ds, it["id"] as Int)!!, it, *keys)
    ) as T
}

interface Bob {
    val x: Int
    val y: String?
}

class RealBob(private val ds: DataSource, val id: Int) : Bob {
    override val x: Int
        get() =
            ds.fetch("SELECT x WHERE ID = :id", id).first()["x"] as Int

    override val y: String?
        get() =
            ds.fetch("SELECT y WHERE ID = :id", id).first()["y"] as String?

    override fun equals(other: Any?) = this === other ||
            other is RealBob &&
            id == other.id

    override fun hashCode() = Objects.hash(this::class, id)

    override fun toString() = "RealBob($id){x=$x, y=$y}"
}
