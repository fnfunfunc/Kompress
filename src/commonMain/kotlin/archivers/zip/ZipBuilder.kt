package archivers.zip

import okio.*
import okio.ByteString.Companion.encodeUtf8
import utils.CRC32
import utils.FileUtil
import utils.checksum

object ZipBuilder {

    suspend fun createZipFromTree(file: Path, outputPath: Path){
        val outputFile = FileUtil.fileSystem.openReadWrite(outputPath, mustExist = false)
        val buffer = Buffer()
        createZipFromTreeTo(file, outputFile, buffer)
        outputFile.write(fileOffset = 0, source = buffer, buffer.size)
        buffer.close()
        outputFile.close()
    }

    suspend fun createZipFromTreeTo(file: Path, outputFile: FileHandle, buffer: Buffer) {
        val entries = arrayListOf<ZipEntry>()
        addZipFileEntryTree(file, entries, buffer)
        val directoryStart = buffer.size
        for (entry in entries) {
            addDirEntry(entry, buffer)
        }
        val directoryEnd = buffer.size

        val comment = byteArrayOf()

        (buffer as BufferedSink).apply {
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

    suspend fun addZipFileEntry(entry: Path, buffer: Buffer): ZipEntry {
        val fileHandle = FileUtil.fileSystem.openReadWrite(file = entry)
        val fileMetadata = FileUtil.fileSystem.metadata(entry)
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
        val compressedSize = size
        val uncompressedSize = size


        val headerOffset = buffer.size

        (buffer as BufferedSink).apply {
            write(byteString = "PK\u0003\u0004".encodeUtf8())
            writeShortLe(extractVersion)
            writeShortLe(flags)
            writeShortLe(compressionMethod)
            writeShortLe(date)
            writeShortLe(time)
            writeIntLe(crc32)
            writeIntLe(compressedSize)
            writeIntLe(uncompressedSize)
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
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize,
            nameBytes = nameBytes,
            extraBytes = extraBytes,
            commentBytes = byteArrayOf(),
            diskNumberStart = 0,
            internalAttributes = 0,
            externalAttributes = 0
        )
    }

    private suspend fun addZipFileEntryTree(entry: Path, entries: MutableList<ZipEntry>, buffer: Buffer) {
        if (FileUtil.fileSystem.metadata(entry).isDirectory) {
            FileUtil.fileSystem.list(entry).forEach { addZipFileEntryTree( it, entries, buffer) }
        } else {
            entries += addZipFileEntry(entry, buffer)
        }
    }

    private suspend fun addDirEntry(entry: ZipEntry, buffer: Buffer) {
        (buffer as BufferedSink).apply {
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