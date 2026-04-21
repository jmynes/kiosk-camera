package com.kioskcamera

import android.content.Context
import android.util.Log
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.xfer.FileSystemFile
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

object ScpUploader {

    private const val TAG = "ScpUploader"
    private const val KEY_FILE = "id_rsa"
    private const val PUB_KEY_FILE = "id_rsa.pub"

    private var keyDir: File? = null

    private val initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        // Register BouncyCastle provider for SSHJ (fast, stays on main thread)
        java.security.Security.removeProvider("BC")
        java.security.Security.insertProviderAt(
            org.bouncycastle.jce.provider.BouncyCastleProvider(), 1
        )

        keyDir = File(context.filesDir, "ssh_keys")
        keyDir!!.mkdirs()
        if (!File(keyDir, KEY_FILE).exists()) {
            // 4096-bit RSA keygen is expensive — run in background
            initExecutor.execute { generateKeyPair() }
        }
    }

    private fun generateKeyPair() {
        val dir = keyDir ?: return
        Log.i(TAG, "Generating SSH keypair...")

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(4096)
        val kp = kpg.generateKeyPair()

        // Save private key in PKCS#8 PEM format (what SSHJ expects)
        val privKey = kp.private as RSAPrivateKey
        val privPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(76, "\n".toByteArray())
                    .encodeToString(privKey.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        File(dir, KEY_FILE).writeText(privPem)

        // Save public key in OpenSSH format
        val pubKey = kp.public as RSAPublicKey
        val pubBytes = encodeOpenSshPublicKey(pubKey)
        val pubStr = "ssh-rsa ${Base64.getEncoder().encodeToString(pubBytes)} kioskcamera@device"
        File(dir, PUB_KEY_FILE).writeText(pubStr)

        Log.i(TAG, "SSH keypair generated")
        Log.i(TAG, "Public key: $pubStr")
    }

    private fun encodeOpenSshPublicKey(key: RSAPublicKey): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val type = "ssh-rsa".toByteArray()
        writeBytes(out, type)
        writeBytes(out, key.publicExponent.toByteArray())
        writeBytes(out, key.modulus.toByteArray())
        return out.toByteArray()
    }

    private fun writeBytes(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        val len = data.size
        out.write(len shr 24 and 0xFF)
        out.write(len shr 16 and 0xFF)
        out.write(len shr 8 and 0xFF)
        out.write(len and 0xFF)
        out.write(data)
    }

    fun getPublicKey(): String {
        val dir = keyDir ?: return ""
        val pubFile = File(dir, PUB_KEY_FILE)
        return if (pubFile.exists()) pubFile.readText() else ""
    }

    fun uploadFile(file: File, host: String, port: Int, username: String, remotePath: String): Boolean {
        return uploadFileAs(file, file.name, host, port, username, remotePath)
    }

    fun uploadBatch(
        files: List<Pair<File, String>>, // file to remote filename
        host: String, port: Int, username: String, remotePath: String,
        onProgress: ((String) -> Unit)? = null
    ): Pair<Int, Int> {
        val dir = keyDir ?: return Pair(0, files.size)
        val privKeyFile = File(dir, KEY_FILE)
        if (!privKeyFile.exists()) return Pair(0, files.size)

        val config = DefaultConfig()
        val ssh = SSHClient(config)
        var uploaded = 0
        var failed = 0

        try {
            Log.i(TAG, "Connecting to $host:$port as $username")
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 60000
            ssh.timeout = 120000
            ssh.connect(host, port)

            val keyProvider = ssh.loadKeys(privKeyFile.absolutePath)
            ssh.authPublickey(username, keyProvider)

            // Create remote directory
            Log.i(TAG, "Creating remote dir: $remotePath")
            val mkdirSession = ssh.startSession()
            mkdirSession.exec("mkdir -p '$remotePath'").join()
            mkdirSession.close()

            // Upload each file
            for ((file, remoteName) in files) {
                if (!file.exists()) continue
                try {
                    onProgress?.invoke("Uploading $remoteName...")
                    val dest = "$remotePath/$remoteName"
                    ssh.newSCPFileTransfer().upload(FileSystemFile(file), dest)
                    uploaded++
                    Log.i(TAG, "SCP uploaded: $remoteName to $host:$dest")
                } catch (e: Exception) {
                    failed++
                    Log.e(TAG, "SCP upload failed for $remoteName: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SCP connection failed: ${e.javaClass.simpleName}: ${e.message}")
            failed = files.size - uploaded
        } finally {
            try { ssh.disconnect() } catch (_: Exception) {}
        }

        return Pair(uploaded, failed)
    }

    private fun uploadFileAs(file: File, remoteName: String, host: String, port: Int, username: String, remotePath: String): Boolean {
        val dir = keyDir ?: return false
        val privKeyFile = File(dir, KEY_FILE)
        if (!privKeyFile.exists()) return false

        val config = DefaultConfig()
        val ssh = SSHClient(config)
        return try {
            Log.i(TAG, "Connecting to $host:$port as $username")
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 60000
            ssh.timeout = 120000
            ssh.connect(host, port)

            val keyProvider = ssh.loadKeys(privKeyFile.absolutePath)
            ssh.authPublickey(username, keyProvider)

            ssh.newSCPFileTransfer().upload(FileSystemFile(file), remotePath)
            Log.i(TAG, "SCP uploaded: ${file.name} to $host:$remotePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SCP upload failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            try { ssh.disconnect() } catch (_: Exception) {}
        }
    }
}
