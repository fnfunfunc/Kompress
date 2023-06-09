package archivers.zip

import okio.*
import okio.ByteString.Companion.encodeUtf8
import utils.CRC32
import utils.FileUtil
import utils.checksum

object ZipBuilder {

    fun createZipFromTree(inputPath: Path, outputPath: Path){
        val outputFile = FileUtil.fileSystem.openReadWrite(outputPath, mustExist = false)
        val sink = outputFile.sink()
        val buffer = Buffer()
        createZipFromTreeTo(inputPath, buffer)
        sink.write(buffer, buffer.size)
        buffer.close()
        outputFile.close()
    }

    private fun createZipFromTreeTo(inputPath: Path, buffer: Buffer) {
        val entries = mutableListOf<ZipEntry>()
        addZipFileEntryTree(inputPath, entries, buffer)

        val directoryStart = buffer.size
        for (entry in entries) {
            addDirEntry(entry, buffer)
        }
        val directoryEnd = buffer.size
        val comment = byteArrayOf()

        buffer.apply {
            write(byteString = "PK\u0005\u0006".encodeUtf8())
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(entries.size)
            writeShortLe(entries.size)
            writeIntLe((directoryEnd - directoryStart).toInt())
            writeIntLe(directoryStart.toInt())
            writeShortLe(comment.size)
            write(comment)
        }
    }

    private fun addZipFileEntry(entry: Path, buffer: Buffer): ZipEntry {
        val fileHandle = FileUtil.fileSystem.openReadOnly(file = entry)
        val size = fileHandle.size().toInt()
        val versionMadeBy = 0x314
        val extractVersion = 10
        val flags = 2048
        //val compressionMethod = 8 // Deflate
        val compressionMethod = 0 // Store
        val date = 0
        val time = 0
        val crc32 = fileHandle.source().checksum(CRC32)
        val name = entry.name.trim('/')//entry.segments.last()
        val nameBytes = name.encodeUtf8().toByteArray()
        val extraBytes = byteArrayOf()


        val headerOffset = buffer.size

        buffer.apply {
            write(byteString = "PK\u0003\u0004".encodeUtf8())
            writeShortLe(extractVersion)
            writeShortLe(flags)
            writeShortLe(compressionMethod)
            writeShortLe(date)
            writeShortLe(time)
            writeIntLe(crc32)
            writeIntLe(size)
            writeIntLe(size)
            writeShortLe(nameBytes.size)
            writeShortLe(extraBytes.size)
            write(nameBytes)
            write(extraBytes)
        }

        val source = fileHandle.source(fileOffset = 0)
        buffer.writeAll(source)

        return ZipEntry(
            versionMadeBy = versionMadeBy,
            extractVersion = extractVersion,
            headerOffset = headerOffset,
            compressionMethod = compressionMethod,
            flags = flags,
            date = date,
            time = time,
            crc32 = crc32,
            compressedSize = size,
            uncompressedSize = size,
            nameBytes = nameBytes,
            extraBytes = extraBytes,
            commentBytes = byteArrayOf(),
            diskNumberStart = 0,
            internalAttributes = 0,
            externalAttributes = 0
        )
    }

    private fun addZipFileEntryTree(entry: Path, entries: MutableList<ZipEntry>, buffer: Buffer) {
        if (FileUtil.fileSystem.metadata(entry).isDirectory) {
            FileUtil.fileSystem.list(entry).forEach {
                addZipFileEntryTree(it, entries, buffer)
            }
        } else {
            entries.add(addZipFileEntry(entry, buffer))
        }
    }

    private fun addDirEntry(entry: ZipEntry, buffer: Buffer) {
        buffer.apply {
            write("PK\u0001\u0002".encodeUtf8())
            writeShortLe(entry.versionMadeBy)
            writeShortLe(entry.extractVersion)
            writeShortLe(entry.flags)
            writeShortLe(entry.compressionMethod)
            writeShortLe(entry.date)
            writeShortLe(entry.time)
            writeIntLe(entry.crc32)
            writeIntLe(entry.compressedSize)
            writeIntLe(entry.uncompressedSize)
            writeShortLe(entry.nameBytes.size)
            writeShortLe(entry.extraBytes.size)
            writeShortLe(entry.commentBytes.size)
            writeShortLe(entry.diskNumberStart)
            writeShortLe(entry.internalAttributes)
            writeIntLe(entry.externalAttributes)
            writeIntLe(entry.headerOffset.toInt())
            write(entry.nameBytes)
            write(entry.extraBytes)
            write(entry.commentBytes)
        }
    }
}