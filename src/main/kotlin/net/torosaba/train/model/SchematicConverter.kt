package net.torosaba.train.model

import net.torosaba.train.util.Nbt
import net.torosaba.train.util.NbtCompound
import net.torosaba.train.util.NbtList
import org.bukkit.Bukkit
import org.bukkit.Material
import java.io.File

/**
 * WorldEdit のスケマティック (.schem、Sponge 形式の版 1 - 3 と、古い MCEdit 形式の .schematic) を、Structure の形式 (.nbt) に写す。
 * ブロックエンティティ (看板の文字やチェストの中身) のデータもそのまま写すので失われない。
 * DataVersion も写すので、古い版で作ったスケマティックは読み込むときにゲームが今の版に直す。
 */
object SchematicConverter {
    class Result(val width: Int, val height: Int, val length: Int, val blocks: Int)

    // DataVersion を持たない形式は 1.13.2 で作られたとみなす (WorldEdit と同じ)
    private const val ASSUMED_DATA_VERSION = 1631

    fun convert(schem: File, out: File): Result {
        val (_, root) = Nbt.readFile(schem)
        val s = root.compound("Schematic") ?: root
        val width = s.int("Width") ?: error("Width がありません")
        val height = s.int("Height") ?: error("Height がありません")
        val length = s.int("Length") ?: error("Length がありません")
        val output = Output(width, height, length, s.int("DataVersion") ?: ASSUMED_DATA_VERSION)
        if (s["Blocks"] is ByteArray) readLegacy(s, output) else readSponge(s, output)
        return output.write(out)
    }

    /** 書き出す Structure。パレットは使うものだけにする */
    private class Output(val width: Int, val height: Int, val length: Int, val dataVersion: Int) {
        val palette = ArrayList<Any>()
        val paletteIndex = HashMap<String, Int>()
        val blocks = ArrayList<Any>()

        fun add(index: Int, name: String, nbt: NbtCompound?) {
            if (isAir(name)) return
            val state = paletteIndex.getOrPut(name) {
                palette += paletteEntry(name)
                palette.size - 1
            }
            val block = NbtCompound()
            block["pos"] = NbtList(listOf(index % width, index / (width * length), (index / width) % length))
            block["state"] = state
            nbt?.let { block["nbt"] = it }
            blocks += block
        }

        fun write(out: File): Result {
            val structure = NbtCompound()
            structure["DataVersion"] = dataVersion
            structure["size"] = NbtList(listOf(width, height, length))
            structure["palette"] = NbtList(Nbt.COMPOUND, palette)
            structure["blocks"] = NbtList(Nbt.COMPOUND, blocks)
            structure["entities"] = NbtList(Nbt.COMPOUND, ArrayList())
            Nbt.writeFile(out, "", structure)
            return Result(width, height, length, blocks.size)
        }
    }

    private fun readSponge(s: NbtCompound, output: Output) {
        val width = output.width
        val length = output.length
        // 版 3 は Blocks の中、版 1・2 はいちばん外にある
        val blocks = s.compound("Blocks")
        val paletteTag = (blocks ?: s).compound("Palette") ?: error("Palette がありません")
        val data = ((blocks ?: s)[if (blocks != null) "Data" else "BlockData"] as? ByteArray) ?: error("ブロックのデータがありません")
        val entities = (blocks ?: s).list("BlockEntities") ?: s.list("TileEntities")

        val names = HashMap<Int, String>()
        for ((name, id) in paletteTag) names[(id as Number).toInt()] = name

        // ブロックエンティティを位置で引けるようにする
        val entityAt = HashMap<Int, NbtCompound>()
        for (e in entities?.items.orEmpty()) {
            val tag = e as? NbtCompound ?: continue
            val pos = tag["Pos"] as? IntArray ?: continue
            val nbt = NbtCompound()
            val inner = tag.compound("Data")
            if (inner != null) nbt.putAll(inner) else tag.forEach { (k, v) -> if (k != "Pos" && k != "Id") nbt[k] = v }
            tag.string("Id")?.let { nbt["id"] = it }
            nbt.remove("x"); nbt.remove("y"); nbt.remove("z")
            entityAt[pos[0] + pos[2] * width + pos[1] * width * length] = nbt
        }

        var index = 0
        var offset = 0
        while (offset < data.size && index < width * output.height * length) {
            // 可変長の整数 (varint) でパレットの番号が並んでいる
            var value = 0
            var shift = 0
            while (true) {
                val b = data[offset++].toInt()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            output.add(index, names[value] ?: "minecraft:air", entityAt[index])
            index++
        }
    }

    /** MCEdit 形式 (1.12 までの .schematic)。ブロックは番号とデータ値で並び、ブロックエンティティは x, y, z を持つ */
    @Suppress("DEPRECATION")
    private fun readLegacy(s: NbtCompound, output: Output) {
        val width = output.width
        val length = output.length
        val ids = s["Blocks"] as ByteArray
        val values = s["Data"] as? ByteArray ?: error("Data がありません")
        val add = s["AddBlocks"] as? ByteArray

        val entityAt = HashMap<Int, NbtCompound>()
        for (e in s.list("TileEntities")?.items.orEmpty()) {
            val tag = e as? NbtCompound ?: continue
            val x = tag.int("x") ?: continue
            val y = tag.int("y") ?: continue
            val z = tag.int("z") ?: continue
            val nbt = NbtCompound()
            tag.forEach { (k, v) -> if (k != "x" && k != "y" && k != "z") nbt[k] = v }
            // 古い名前 (Sign など) を今の名前の形にする
            tag.string("id")?.let { nbt["id"] = if (':' in it) it else "minecraft:" + it.lowercase() }
            entityAt[x + z * width + y * width * length] = nbt
        }

        // Paper は Material の一覧から古いマテリアルを隠し、名前で引くと今のマテリアルに直すので、定数から直接取る
        val materials = Material::class.java.fields.asSequence()
            .filter { it.name.startsWith(Material.LEGACY_PREFIX) }
            .mapNotNull { it.get(null) as? Material }
            .filter { it.isLegacy }
            .associateBy { it.id }
        val names = HashMap<Int, String>()
        val unsafe = Bukkit.getUnsafe()
        for (index in ids.indices) {
            var id = ids[index].toInt() and 0xFF
            if (add != null && index shr 1 < add.size) {
                val nibble = add[index shr 1].toInt()
                id += if (index and 1 == 0) (nibble and 0x0F) shl 8 else (nibble and 0xF0) shl 4
            }
            val data = values.getOrElse(index) { 0 }.toInt() and 0x0F
            val name = names.getOrPut(id * 16 + data) {
                materials[id]?.let { unsafe.fromLegacy(it, data.toByte()).asString } ?: "minecraft:air"
            }
            output.add(index, name, entityAt[index])
        }
    }

    private fun isAir(name: String) =
        name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air" || name == "minecraft:structure_void"

    // "minecraft:oak_stairs[facing=north,half=bottom]" → {Name: ..., Properties: {facing: north, half: bottom}}
    private fun paletteEntry(text: String): NbtCompound {
        val entry = NbtCompound()
        val bracket = text.indexOf('[')
        entry["Name"] = if (bracket < 0) text else text.substring(0, bracket)
        if (bracket >= 0 && text.endsWith("]")) {
            val props = NbtCompound()
            for (pair in text.substring(bracket + 1, text.length - 1).split(',')) {
                val eq = pair.indexOf('=')
                if (eq > 0) props[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            if (props.isNotEmpty()) entry["Properties"] = props
        }
        return entry
    }
}
