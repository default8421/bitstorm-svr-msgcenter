package cn.bitoffer.msgcenter.core.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前请求的租户：取登录用户名。未登录返回 null。
 *
 * @author LQH
 */
public final class TenantContext {

    private TenantContext() {
    }

    public static String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal == null || "anonymousUser".equals(principal)) {
            return null;
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? null : name;
    }

    public static String require() {
        String tenant = current();
        if (tenant == null) {
            throw new IllegalStateException("未登录，无法确定租户");
        }
        return tenant;
    }
}
