package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class BackupCryptoSupportTest {
  private static final String VALID_KEY =
      Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void test_encrypt_decrypt_roundtrip_recoversPlaintext() {
    BackupCryptoSupport crypto = new BackupCryptoSupport(properties(VALID_KEY));

    String cipher = crypto.encrypt("p@ssw0rd-远程-密码");

    assertThat(cipher).startsWith("v1:");
    assertThat(crypto.decrypt(cipher)).isEqualTo("p@ssw0rd-远程-密码");
  }

  @Test
  void test_encrypt_samePlaintext_producesDistinctCiphertexts() {
    BackupCryptoSupport crypto = new BackupCryptoSupport(properties(VALID_KEY));

    String first = crypto.encrypt("same-secret");
    String second = crypto.encrypt("same-secret");

    assertThat(first).isNotEqualTo(second);
    assertThat(crypto.decrypt(first)).isEqualTo(crypto.decrypt(second));
  }

  @Test
  void test_decrypt_tamperedCiphertext_failsWithCorruptionMessage() {
    BackupCryptoSupport crypto = new BackupCryptoSupport(properties(VALID_KEY));
    String cipher = crypto.encrypt("secret");
    char[] chars = cipher.toCharArray();
    chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

    assertThatThrownBy(() -> crypto.decrypt(new String(chars)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("密文已损坏");
  }

  @Test
  void test_decrypt_withDifferentKey_failsRatherThanReturnsGarbage() {
    byte[] otherKeyBytes = new byte[32];
    otherKeyBytes[0] = 7;
    BackupCryptoSupport writer = new BackupCryptoSupport(properties(VALID_KEY));
    BackupCryptoSupport reader =
        new BackupCryptoSupport(properties(Base64.getEncoder().encodeToString(otherKeyBytes)));
    String cipher = writer.encrypt("secret");

    assertThatThrownBy(() -> reader.decrypt(cipher))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("主密钥不匹配");
  }

  @Test
  void test_operations_withoutConfiguredKey_rejectedExplicitly() {
    BackupCryptoSupport crypto = new BackupCryptoSupport(properties(""));

    assertThat(crypto.isConfigured()).isFalse();
    assertThatThrownBy(() -> crypto.encrypt("secret"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("PLATFORM_BACKUP_SECRET_KEY");
    assertThatThrownBy(() -> crypto.decrypt("v1:abc:def"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("PLATFORM_BACKUP_SECRET_KEY");
  }

  @Test
  void test_construction_withMalformedKey_failsAtStartupWithGuidance() {
    assertThatThrownBy(() -> new BackupCryptoSupport(properties("not-base64!!")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("openssl rand -base64 32");
  }

  @Test
  void test_construction_withWrongKeyLength_failsAtStartup() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new BackupCryptoSupport(properties(shortKey)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("32 字节");
  }

  private BackupConfigurationProperties properties(String secretKey) {
    BackupConfigurationProperties properties = new BackupConfigurationProperties();
    properties.setSecretKey(secretKey);
    return properties;
  }
}
