package net.torosaba.train.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * NBT (Minecraft のデータ形式) の読み書き。WorldEdit のスケマティックを読み、Structure の形式で書くのに使う。
 *
 * 値は Kotlin の型で表す: Byte, Short, Int, Long, Float, Double, ByteArray, String, NbtList, NbtCompound, IntArray, LongArray
 */
object Nbt {
    const val END = 0
    const val BYTE = 1
    const val SHORT = 2
    const val INT = 3
    const val LONG = 4
    const val FLOAT = 5
    const val DOUBLE = 6
    const val BYTE_ARRAY = 7
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INT_ARRAY = 11
    const val LONG_ARRAY = 12

    /** gzip で圧縮した NBT ファイルを読む。いちばん外の名前と中身を返す */
    fun readFile(file: File): Pair<String, NbtCompound> = file.inputStream().buffered().use { read(GZIPInputStream(it)) }

    fun read(input: InputStream): Pair<String, NbtCompound> {
        val data = DataInputStream(input)
        val type = data.readByte().toInt()
        require(type == COMPOUND) { "NBT の始まりが compound ではありません ($type)" }
        val name = data.readUTF()
        return name to readPayload(data, COMPOUND, 0) as NbtCompound
    }

    private const val MAX_DEPTH = 512
    private const val MAX_ARRAY_SIZE = 16 * 1024 * 1024

    private fun readPayload(data: DataInputStream, type: Int, depth: Int): Any {
        require(depth <= MAX_DEPTH) { "NBT のネストが深すぎます ($depth > $MAX_DEPTH)" }
        return when (type) {
            BYTE -> data.readByte()
            SHORT -> data.readShort()
            INT -> data.readInt()
            LONG -> data.readLong()
            FLOAT -> data.readFloat()
            DOUBLE -> data.readDouble()
            BYTE_ARRAY -> {
                val size = data.readInt()
                require(size in 0..MAX_ARRAY_SIZE) { "BYTE_ARRAY のサイズが不正です ($size)" }
                ByteArray(size).also { data.readFully(it) }
            }
            STRING -> data.readUTF()
            LIST -> {
                val elementType = data.readByte().toInt()
                val size = data.readInt()
                require(size in 0..MAX_ARRAY_SIZE) { "LIST のサイズが不正です ($size)" }
                NbtList(elementType, MutableList(size) { readPayload(data, elementType, depth + 1) })
            }
            COMPOUND -> {
                val map = NbtCompound()
                while (true) {
                    val t = data.readByte().toInt()
                    if (t == END) break
                    val key = data.readUTF()
                    map[key] = readPayload(data, t, depth + 1)
                }
                map
            }
            INT_ARRAY -> {
                val size = data.readInt()
                require(size in 0..MAX_ARRAY_SIZE) { "INT_ARRAY のサイズが不正です ($size)" }
                IntArray(size) { data.readInt() }
            }
            LONG_ARRAY -> {
                val size = data.readInt()
                require(size in 0..MAX_ARRAY_SIZE) { "LONG_ARRAY のサイズが不正です ($size)" }
                LongArray(size) { data.readLong() }
            }
            else -> throw IllegalArgumentException("知らない NBT の型 $type")
        }
    }

    /** gzip で圧縮した NBT ファイルに書く */
    fun writeFile(file: File, name: String, root: NbtCompound) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out -> GZIPOutputStream(out).use { write(it, name, root) } }
    }

    fun write(output: OutputStream, name: String, root: NbtCompound) {
        val data = DataOutputStream(output)
        data.writeByte(COMPOUND)
        data.writeUTF(name)
        writePayload(data, root)
        data.flush()
    }

    fun typeOf(value: Any): Int = when (value) {
        is Byte -> BYTE
        is Short -> SHORT
        is Int -> INT
        is Long -> LONG
        is Float -> FLOAT
        is Double -> DOUBLE
        is ByteArray -> BYTE_ARRAY
        is String -> STRING
        is NbtList -> LIST
        is NbtCompound -> COMPOUND
        is IntArray -> INT_ARRAY
        is LongArray -> LONG_ARRAY
        is Boolean -> BYTE
        else -> throw IllegalArgumentException("NBT にできない値 ${value::class.simpleName}")
    }

    private fun writePayload(data: DataOutputStream, value: Any) {
        when (value) {
            is Byte -> data.writeByte(value.toInt())
            is Boolean -> data.writeByte(if (value) 1 else 0)
            is Short -> data.writeShort(value.toInt())
            is Int -> data.writeInt(value)
            is Long -> data.writeLong(value)
            is Float -> data.writeFloat(value)
            is Double -> data.writeDouble(value)
            is ByteArray -> { data.writeInt(value.size); data.write(value) }
            is String -> data.writeUTF(value)
            is NbtList -> {
                val type = if (value.items.isEmpty()) END else typeOf(value.items.first())
                data.writeByte(type)
                data.writeInt(value.items.size)
                for (item in value.items) writePayload(data, item)
            }
            is NbtCompound -> {
                for ((key, v) in value) {
                    data.writeByte(typeOf(v))
                    data.writeUTF(key)
                    writePayload(data, v)
                }
                data.writeByte(END)
            }
            is IntArray -> { data.writeInt(value.size); value.forEach { data.writeInt(it) } }
            is LongArray -> { data.writeInt(value.size); value.forEach { data.writeLong(it) } }
            else -> throw IllegalArgumentException("NBT にできない値 ${value::class.simpleName}")
        }
    }
}

class NbtCompound : LinkedHashMap<String, Any>() {
    fun compound(key: String): NbtCompound? = this[key] as? NbtCompound
    fun list(key: String): NbtList? = this[key] as? NbtList
    fun int(key: String): Int? = (this[key] as? Number)?.toInt()
    fun string(key: String): String? = this[key] as? String
}

class NbtList(val elementType: Int, val items: MutableList<Any>) {
    constructor(items: List<Any>) : this(if (items.isEmpty()) Nbt.END else Nbt.typeOf(items.first()), items.toMutableList())
}
