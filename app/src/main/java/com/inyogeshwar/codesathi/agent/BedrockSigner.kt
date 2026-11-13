package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signing for Bedrock (Phase 4). Hand-rolled:
 * no AWS SDK — the app adds no new dependency for one provider.
 *
 * Signs POST https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse
 * (service `bedrock-runtime`) with the signed headers
 * `content-type;host;x-amz-content-sha256;x-amz-date`. The secret access
 * key is used only inside the HMAC chain here and is never logged.
 *
 * Verification (no JVM in this VM, so the Kotlin below was checked by
 * mirroring it step-for-step in Python): the same code signs AWS's own
 * worked example (IAM ListUsers, 20150830T123600Z) to the published
 * canonical hash f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59
 * and signature 5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7.
 * For the fixed Bedrock test input (model anthropic.claude-sonnet-4-5,
 * region us-east-1, body
 * {"messages":[{"role":"user","content":[{"text":"Hello"}]}],
 * "inferenceConfig":{"maxTokens":4096}}, date 20261113T120000Z, the
 * example credentials AKIDEXAMPLE / wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY)
 * the signature must be
 * 5db068f009e7b70cbf1cbc933600b5155d09e4d537e5e2f96f72d02c749191a2
 * and the canonical hash
 * 5dd1c8fd17f00913bcbb6e9e7001a7e1b23f67a7aaa30775833453143a7edeba.
 * If a first on-device Bedrock call is rejected, compare those two values
 * first — they pin down whether the signer or something else is wrong.
 */
object BedrockSigner {

    /** What the caller puts on the request: the auth header + the two signed headers. */
    data class SignedRequest(
        val authorization: String,
        val amzDate: String,
        val payloadHash: String
    )

    /**
     * Sign one Bedrock Converse POST. [path] is the request path with the
     * model id already percent-encoded (see [conversePath]) — the same
     * string must go in the URL, or AWS rejects the signature.
     * [nowMillis] exists so tests can pin the date; callers pass nothing.
     */
    fun sign(
        credentials: KeyStore.AwsCredentials,
        host: String,
        path: String,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): SignedRequest {
        val amzDate = formatAmzDate(nowMillis)
        val dateStamp = amzDate.substring(0, 8)
        val payloadHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date"
        val canonicalHeaders = "content-type:application/json\n" +
            "host:$host\n" +
            "x-amz-content-sha256:$payloadHash\n" +
            "x-amz-date:$amzDate\n"
        val canonicalRequest = "POST\n$path\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$dateStamp/${credentials.region}/bedrock-runtime/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" +
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        val signingKey = deriveSigningKey(credentials.secretAccessKey, dateStamp, credentials.region)
        val signature = hmacHex(signingKey, stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        return SignedRequest(authorization, amzDate, payloadHash)
    }

    /** The Converse path for one model id, with the id percent-encoded. */
    fun conversePath(modelId: String): String = "/model/${encodeModelId(modelId)}/converse"

    /**
     * Percent-encode one path segment: every byte except the unreserved
     * set A–Z a–z 0–9 - _ . ~ becomes %XX (uppercase hex). Model ids like
     * `anthropic.claude-sonnet-4-5` and inference-profile ids like
     * `us.anthropic.claude-sonnet-4-5` pass through unchanged. ARNs
     * (which contain `:` and `/`) are not supported — the `:` would be
     * encoded and the `/` would split the path, so such an id fails
     * loudly at the API instead of silently signing a different request.
     */
    fun encodeModelId(modelId: String): String {
        val bytes = modelId.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val unreserved = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code
            if (unreserved) out.append(c.toChar()) else out.append("%" + "%02X".format(c))
        }
        return out.toString()
    }

    private fun deriveSigningKey(secret: String, dateStamp: String, region: String): ByteArray {
        var key = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        key = hmac(key, region)
        key = hmac(key, "bedrock-runtime")
        return hmac(key, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String =
        hmac(key, data).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** `yyyyMMdd'T'HHmmss'Z'` in UTC — SimpleDateFormat, so it works on API 24 (no java.time). */
    private fun formatAmzDate(nowMillis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMillis))
    }
}
