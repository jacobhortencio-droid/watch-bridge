package io.github.jacobhortencio.watchbridge.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia do emparelhamento:
 * troca de chaves ECDH (P-256), código de verificação de 6 dígitos mostrado nos dois ecrãs
 * (protege contra intermediários) e cifra AES-256-GCM das mensagens.
 */
public final class Secure {
    private Secure() {}

    private static final SecureRandom RNG = new SecureRandom();

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return g.generateKeyPair();
    }

    public static String pub(KeyPair kp) {
        return b64(kp.getPublic().getEncoded());
    }

    /** Chave de sessão de 32 bytes derivada do segredo ECDH e das duas chaves públicas. */
    public static byte[] derive(KeyPair mine, String peerPub, String pubPhone, String pubWatch)
            throws GeneralSecurityException {
        PublicKey p = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(peerPub)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine.getPrivate());
        ka.doPhase(p, true);
        byte[] secret = ka.generateSecret();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(secret);
        md.update(("wb-key-v1|" + pubPhone + "|" + pubWatch).getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    /** Código de verificação de 6 dígitos (igual nos dois aparelhos se não houver intermediário). */
    public static String sas(String pubPhone, String pubWatch) {
        byte[] h = sha256((pubPhone + "|" + pubWatch).getBytes(StandardCharsets.UTF_8));
        int v = ((h[0] & 0xff) << 16) | ((h[1] & 0xff) << 8) | (h[2] & 0xff);
        return String.format(Locale.US, "%06d", v % 1_000_000);
    }

    /** Identificador curto da chave, para saber se os dois lados têm a mesma. */
    public static String keyId(byte[] key) {
        if (key == null) return "";
        byte[] h = sha256(key);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(String.format(Locale.US, "%02x", h[i]));
        return sb.toString();
    }

    public static String encrypt(byte[] key, byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return b64(out);
    }

    public static byte[] decrypt(byte[] key, String data) throws GeneralSecurityException {
        byte[] in = unb64(data);
        if (in.length < 13) throw new GeneralSecurityException("curto");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
        return c.doFinal(in, 12, in.length - 12);
    }

    public static String hmac(byte[] key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return b64(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    public static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s == null ? "" : s);
    }

    public static byte[] gzip(byte[] b) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(bos)) {
                z.write(b);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] gunzip(byte[] b) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(b))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = z.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8_000_000) throw new IOException("demasiado grande");
            }
            return bos.toByteArray();
        }
    }
}
