package com.sorrowblue.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.documentfile.provider.DocumentFile
import com.sorrowblue.android.ui.theme.KioArchTheme
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.SeekableSource
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.readByteArray
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.io.println
import kotlin.use

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KioArchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var input: Uri? by remember { mutableStateOf(null) }
    val outputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                input?.let {
                    saveFileInFolder(context, UriSeekableSource(context, input!!), uri)
                }
            }
        }
    )
    val launncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                input = uri
                test(UriSeekableSource(context, uri))
                outputLauncher.launch(null)
            }
        }
    )
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(text = "Hello $name!")
            })
        },
        modifier = modifier
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {
            Button(onClick = {
                launncher.launch(arrayOf("*/*"))
            }) {
                Text("7zファイルを選択")
            }
        }

    }
}

fun saveFileInFolder(context: Context, input: SeekableSource, folderUri: Uri) {
    val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
    KioArch.createReader(input).use { reader ->
        reader.getEntries().forEach {
            if (it.isDirectory) {
                rootDoc?.createDirectory(it.name)
            } else {
                val buffer = Buffer()
                reader.extractEntry(it, buffer)
                val newFile = rootDoc?.createFile("*/*", it.name)
                newFile?.uri?.let { fileUri ->
                    context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                        outputStream.asSink().use {
                            it.write(buffer, buffer.size)
                            it.flush()
                        }
                    }
                }
            }
        }
    }

}

private fun test(source: SeekableSource) {
    KioArch.createReader(source).use { reader ->
        val entries = reader.getEntries()

        println("Found ${entries.size} entries in test.7z")
        for (entry in entries.take(5)) {
            println("Entry: name=${entry.name}, size=${entry.size}, isDir=${entry.isDirectory}, crc=${entry.crc}")
        }

        // Find the first non-directory entry to test extraction
        val fileEntry = entries.firstOrNull { !it.isDirectory && it.size > 0 }
        if (fileEntry != null) {
            println("Extracting entry: ${fileEntry.name} (${fileEntry.size} bytes)...")
            val buffer = Buffer()
            reader.extractEntry(fileEntry, buffer)

            // Assert that the extracted size matches the catalog entry size
//            assertEquals(fileEntry.size, buffer.size)

            // Read out bytes and calculate CRC32 to verify integrity
            val extractedBytes = buffer.readByteArray()
            val crc = java.util.zip.CRC32()
            crc.update(extractedBytes)

            println("Calculated CRC32: ${crc.value}, Archive CRC: ${fileEntry.crc}")
            if (fileEntry.crc != 0L) {
//                assertEquals(fileEntry.crc, crc.value)
            }
        } else {
            println("No non-empty file entries found in test.7z to test extraction.")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    KioArchTheme {
        Greeting("Android")
    }
}

class UriSeekableSource(context: Context, uri: Uri) : SeekableSource {

    // ContentResolver経由でファイルを読み取り専用("r")で開く
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!

    // FileDescriptorからFileChannelを取得（シーク操作に必要）
    private val fis = FileInputStream(pfd.fileDescriptor)
    private val channel = fis.channel

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            // ByteBufferにラップして読み込み
            val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
            val bytesRead = channel.read(byteBuffer)

            // EOF(ファイルの終端)に達した場合は-1を返す（一般的なreadの仕様）
            // インターフェースの仕様に合わせて調整してください
            return bytesRead
        } catch (e: Exception) {
            throw IOException("Error reading from UriSource", e)
        }
    }

    override fun seek(position: Long) {
        try {
            channel.position(position)
        } catch (e: Exception) {
            throw IOException("Error seeking to $position", e)
        }
    }

    override fun position(): Long {
        return channel.position()
    }

    override fun length(): Long {
        // ParcelFileDescriptorからファイルサイズを直接取得
        return pfd.statSize
    }

    override fun close() {
        try {
            channel.close()
            fis.close()
            pfd.close()
        } catch (e: Exception) {
            // クローズ時のエラーはログ程度に留めることが多い
        }
    }
}
