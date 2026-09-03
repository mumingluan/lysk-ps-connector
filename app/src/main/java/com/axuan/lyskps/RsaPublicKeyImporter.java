package com.axuan.lyskps;

import android.content.SharedPreferences;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将标准 PEM RSA 公钥转换为游戏 metadata 中使用的定长 RSAKeyValue 块。 */
public final class RsaPublicKeyImporter {
    private static final int MAX_PEM_BYTES = 1024 * 1024;
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----");

    public static String importPem(SharedPreferences sp, InputStream input, int expectedBits) throws Exception {
        if (expectedBits != 1024 && expectedBits != 2048) {
            throw new IllegalArgumentException("仅支持 1024 或 2048 位 RSA 公钥");
        }
        java.security.interfaces.RSAPublicKey selected = null;
        List<java.security.interfaces.RSAPublicKey> keys = parsePem(input);
        for (java.security.interfaces.RSAPublicKey key : keys) {
            if (key.getModulus().bitLength() == expectedBits) {
                selected = key;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("文件中没有 " + expectedBits + " 位 RSA 公钥");
        }

        Config.load(sp);
        String block = formatBlock(selected, expectedBits);
        if (expectedBits == 2048) {
            Config.setRsaBlocks(sp, block, Config.replace1024B64());
        } else {
            Config.setRsaBlocks(sp, Config.replace2048B64(), block);
        }
        return "已导入 " + expectedBits + " 位 RSA 公钥";
    }

    static List<java.security.interfaces.RSAPublicKey> parsePem(InputStream input) throws Exception {
        byte[] pemBytes = readLimited(input);
        String pem = new String(pemBytes, StandardCharsets.US_ASCII);
        Matcher matcher = PEM_BLOCK.matcher(pem);
        List<java.security.interfaces.RSAPublicKey> keys = new ArrayList<>();
        while (matcher.find()) {
            String type = matcher.group(1);
            byte[] der;
            try {
                der = Base64.decode(matcher.group(2).replaceAll("\\s", ""));
            } catch (Throwable badBase64) {
                throw new IllegalArgumentException("PEM Base64 内容无效", badBase64);
            }
            java.security.PublicKey publicKey;
            switch (type) {
                case "PUBLIC KEY":
                    publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                    break;
                case "RSA PUBLIC KEY":
                    RSAPublicKey rsa = RSAPublicKey.getInstance(ASN1Primitive.fromByteArray(der));
                    publicKey = KeyFactory.getInstance("RSA").generatePublic(
                            new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
                    break;
                case "CERTIFICATE":
                    publicKey = CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(der)).getPublicKey();
                    break;
                default:
                    continue;
            }
            if (!(publicKey instanceof java.security.interfaces.RSAPublicKey)) {
                throw new IllegalArgumentException("PEM 中的公钥不是 RSA");
            }
            keys.add((java.security.interfaces.RSAPublicKey) publicKey);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("未找到 PUBLIC KEY、RSA PUBLIC KEY 或 CERTIFICATE 公钥块");
        }
        return keys;
    }

    static String formatBlock(java.security.interfaces.RSAPublicKey key, int bits) {
        int expectedBytes = bits / 8;
        String modulus = ascii(Base64.encode(unsignedFixed(key.getModulus(), expectedBytes)));
        String exponent = ascii(Base64.encode(unsigned(key.getPublicExponent())));
        String templateB64 = bits == 2048 ? Config.DEFAULT_REPLACE_2048 : Config.DEFAULT_REPLACE_1024;
        String template = ascii(Base64.decode(templateB64));
        template = replaceValue(template, "Modulus", modulus);
        template = replaceValue(template, "Exponent", exponent);
        int expectedLength = bits == 2048 ? 480 : 243;
        if (template.getBytes(StandardCharsets.UTF_8).length != expectedLength) {
            throw new IllegalArgumentException("RSA 公钥格式长度不兼容");
        }
        return ascii(Base64.encode(template.getBytes(StandardCharsets.UTF_8)));
    }

    private static String replaceValue(String template, String tag, String value) {
        String startTag = "<" + tag + ">";
        String endTag = "</" + tag + ">";
        int start = template.indexOf(startTag);
        int end = template.indexOf(endTag, start + startTag.length());
        if (start < 0 || end < 0) throw new IllegalArgumentException("内置 RSA 模板无效");
        start += startTag.length();
        String old = template.substring(start, end);
        int slots = 0;
        for (int i = 0; i < old.length(); i++) if (!Character.isWhitespace(old.charAt(i))) slots++;
        if (slots != value.length()) throw new IllegalArgumentException(tag + " 长度不兼容");
        StringBuilder replacement = new StringBuilder(old.length());
        int valueIndex = 0;
        for (int i = 0; i < old.length(); i++) {
            char c = old.charAt(i);
            replacement.append(Character.isWhitespace(c) ? c : value.charAt(valueIndex++));
        }
        return template.substring(0, start) + replacement + template.substring(end);
    }

    private static byte[] unsignedFixed(BigInteger value, int length) {
        byte[] raw = unsigned(value);
        if (raw.length > length) throw new IllegalArgumentException("RSA 模数超出预期长度");
        byte[] fixed = new byte[length];
        System.arraycopy(raw, 0, fixed, length - raw.length, raw.length);
        return fixed;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("无法读取所选 PEM 文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PEM_BYTES) throw new IllegalArgumentException("PEM 文件不能超过 1 MB");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String ascii(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }

    private RsaPublicKeyImporter() {}
}
