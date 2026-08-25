package com.danidipp.sneakygrounditems.testutil

import org.bukkit.NamespacedKey
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import java.io.IOException
import java.lang.reflect.Proxy

internal fun testPlugin(name: String = "SneakyGroundItemsTest"): Plugin {
    val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
        when (method.name) {
            "getName" -> name
            "namespace" -> name.lowercase()
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "TestPlugin($name)"
            else -> defaultValue(method.returnType)
        }
    }

    return Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java),
        handler,
    ) as Plugin
}

internal class FakePersistentDataHolder : PersistentDataHolder {
    private val container = FakePersistentDataContainer()

    override fun getPersistentDataContainer(): PersistentDataContainer = container
}

internal class FakePersistentDataContainer : PersistentDataContainer {
    private val values = linkedMapOf<NamespacedKey, Any?>()
    private val adapterContext = object : PersistentDataAdapterContext {
        override fun newPersistentDataContainer(): PersistentDataContainer = FakePersistentDataContainer()
    }

    override fun <P : Any, C : Any> set(key: NamespacedKey, type: PersistentDataType<P, C>, value: C) {
        values[key] = value
    }

    override fun remove(key: NamespacedKey) {
        values.remove(key)
    }

    override fun <P : Any, C : Any> has(key: NamespacedKey, type: PersistentDataType<P, C>): Boolean = values.containsKey(key)

    override fun has(key: NamespacedKey): Boolean = values.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun <P : Any, C : Any> get(key: NamespacedKey, type: PersistentDataType<P, C>): C? = values[key] as C?

    override fun <P : Any, C : Any> getOrDefault(
        key: NamespacedKey,
        type: PersistentDataType<P, C>,
        defaultValue: C,
    ): C = get(key, type) ?: defaultValue

    override fun getKeys(): MutableSet<NamespacedKey> = values.keys.toMutableSet()

    override fun isEmpty(): Boolean = values.isEmpty()

    override fun getSize(): Int = values.size

    override fun copyTo(other: PersistentDataContainer, replace: Boolean) {
        for ((key, value) in values) {
            if (!replace && other.has(key)) continue
            when (value) {
                is String -> other.set(key, PersistentDataType.STRING, value)
            }
        }
    }

    override fun getAdapterContext(): PersistentDataAdapterContext = adapterContext

    override fun serializeToBytes(): ByteArray = ByteArray(0)

    @Throws(IOException::class)
    override fun readFromBytes(bytes: ByteArray, clear: Boolean) {
        if (clear) {
            values.clear()
        }
    }
}

internal fun fakeItemDisplay(transformation: Transformation): ItemDisplay {
    val container = FakePersistentDataContainer()
    val location = Location(null, 0.0, 0.0, 0.0)
    val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
        when (method.name) {
            "getTransformation" -> transformation
            "getPersistentDataContainer" -> container
            "getLocation" -> location
            "toString" -> "FakeItemDisplay($transformation)"
            else -> defaultValue(method.returnType)
        }
    }

    return Proxy.newProxyInstance(
        ItemDisplay::class.java.classLoader,
        arrayOf(ItemDisplay::class.java),
        handler,
    ) as ItemDisplay
}

private fun defaultValue(returnType: Class<*>): Any? {
    return when {
        !returnType.isPrimitive -> null
        returnType == Boolean::class.javaPrimitiveType -> false
        returnType == Char::class.javaPrimitiveType -> 0.toChar()
        returnType == Byte::class.javaPrimitiveType -> 0.toByte()
        returnType == Short::class.javaPrimitiveType -> 0.toShort()
        returnType == Int::class.javaPrimitiveType -> 0
        returnType == Long::class.javaPrimitiveType -> 0L
        returnType == Float::class.javaPrimitiveType -> 0f
        returnType == Double::class.javaPrimitiveType -> 0.0
        else -> null
    }
}
