package com.data.collection.platform.service.backup;

import com.data.collection.platform.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 远程备份密码的静态加密支撑：AES-256-GCM，主密钥经 PLATFORM_BACKUP_SECRET_KEY（.env 注入）提供。
 * 密文格式 {@code v1:<base64(iv)>:<base64(ciphertext+tag)>}，版本前缀为将来更换算法预留演进空间。
 * 明文仅在解密瞬间存在于内存，绝不写日志或回落存储；密钥缺失、错钥、篡改与截断一律解密失败。
 */
@Component
public class BackupCryptoSupport {
  private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String FORMAT_VERSION = "v1";
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;
  private static final int KEY_BYTES = 32;

  private final byte[] masterKey;
  private final SecureRandom random = new SecureRandom();

  public BackupCryptoSupport(BackupConfigurationProperties properties) {
    this.masterKey = decodeKey(properties.getSecretKey());
  }

  /** 主密钥是否可用；不可用时仅禁用远程备份（保存远程配置、执行远程备份被明确拒绝）。 */
  public boolean isConfigured() {
    return masterKey != null;
  }

  /** 加密明文；主密钥未配置时抛出业务异常，绝不落库明文。 */
  public String encrypt(String plaintext) {
    requireConfigured();
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(masterKey, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return FORMAT_VERSION
          + ":"
          + Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder().encodeToString(encrypted);
    } catch (GeneralSecurityException failure) {
      throw new BizException("远程密码加密失败：" + failure.getMessage());
    }
  }

  /** 解密密文；错钥、篡改、截断与格式异常统一报"主密钥不匹配或密文已损坏"。 */
  public String decrypt(String ciphertext) {
    requireConfigured();
    String[] parts = ciphertext == null ? new String[0] : ciphertext.split(":", -1);
    if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
      throw new BizException("远程密码解密失败：密文格式不识别，请重新保存远程密码");
    }
    try {
      Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(masterKey, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(parts[1])));
      return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException failure) {
      throw new BizException("远程密码解密失败：主密钥不匹配或密文已损坏，请重新录入远程密码");
    }
  }

  private void requireConfigured() {
    if (masterKey == null) {
      throw new BizException(
          "服务端未配置备份主密钥 PLATFORM_BACKUP_SECRET_KEY（32 字节 base64），无法保存或使用远程密码");
    }
  }

  private static byte[] decodeKey(String configured) {
    if (configured == null || configured.isBlank()) {
      return null;
    }
    try {
      byte[] key = Base64.getDecoder().decode(configured.trim());
      if (key.length != KEY_BYTES) {
        throw new IllegalArgumentException("密钥长度必须为 32 字节，实际 " + key.length + " 字节");
      }
      return key;
    } catch (IllegalArgumentException failure) {
      throw new BizException(
          "PLATFORM_BACKUP_SECRET_KEY 配置非法（需为 base64 编码的 32 字节密钥，可用 openssl rand -base64 32 生成）："
              + failure.getMessage());
    }
  }
}
