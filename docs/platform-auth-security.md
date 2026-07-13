<!-- DOC_STATUS_START -->
> 文档状态：常驻规则
> 说明：继续作为当前工程规则或实现约束使用。
<!-- DOC_STATUS_END -->

# 平台认证安全配置
生产或内网正式部署时，`platform.auth.secure-config-required` 默认为 `true`。本地认证模式下，管理员与审批账号密码必须使用 Spring Security password hash，推荐 `{bcrypt}`。

## 必填配置

```properties
PLATFORM_ADMIN_USERNAME=admin
PLATFORM_ADMIN_PASSWORD={bcrypt}<bcrypt-hash>
PLATFORM_APPROVAL_USERNAME=approval
PLATFORM_APPROVAL_PASSWORD={bcrypt}<bcrypt-hash>
DATASOURCE_URL=jdbc:postgresql://127.0.0.1:15432/qaflex
DATASOURCE_USERNAME=qaflex
DATASOURCE_PASSWORD=change_this_password
GITLAB_WEB_BASE_URL=https://gitlab.example.com
```

当前 Windows 本地开发使用 Docker 容器 `qaflex-dev-postgres-15432`。`qaflex-postgres`
位于 `127.0.0.1:25432`，使用独立的 `qaflex/qaflex` 凭据，不能作为本地后端默认库；以上本地凭据也不能用于生产或内网部署。

`PLATFORM_ADMIN_PASSWORD` 和 `PLATFORM_APPROVAL_PASSWORD` 不允许继续使用明文值。若仍配置明文，应用会在启动阶段失败并提示对应变量必须使用 password hash。

## 生成 bcrypt hash

可以使用 Spring Security 的 `DelegatingPasswordEncoder` 生成带 `{bcrypt}` 前缀的 hash。示例：

```java
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

public class PasswordHashGenerator {
  public static void main(String[] args) {
    System.out.println(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(args[0]));
  }
}
```

临时本地开发可设置 `PLATFORM_SECURE_CONFIG_REQUIRED=false` 保留明文兼容；该配置不应用于正式环境。
