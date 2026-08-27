package cn.bitoffer.msgcenter.core.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 控制台安全配置：静态页和只读接口公开，写接口走 HTTP Basic。
 * 无状态会话，避免高并发下创建 HttpSession。
 *
 * @author LQH
 */
@Configuration
public class SecurityConfiguration {

    /**
     * 单账号运维口令，用恒定时间比较，避免 BCrypt 拖慢认证 QPS。
     *
     * @author LQH
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return rawPassword.toString();
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                byte[] a = rawPassword.toString().getBytes(StandardCharsets.UTF_8);
                byte[] b = encodedPassword.getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(a, b);
            }
        };
    }

    @Bean
    UserDetailsService userDetailsService(PasswordEncoder passwordEncoder,
            @Value("${msgcenter.ops.username:operator}") String username,
            @Value("${msgcenter.ops.password:powergrid-demo}") String password) {
        User.UserBuilder builder = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .roles("OPERATOR");
        return new InMemoryUserDetailsManager(builder.build());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeRequests(authorize -> authorize
                        .antMatchers("/api/hub/stats", "/api/hub/messages", "/actuator/health")
                        .permitAll()
                        .antMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico")
                        .permitAll()
                        .antMatchers("/api/hub/simulate", "/api/hub/sample", "/api/hub/emit")
                        .authenticated()
                        .antMatchers("/msg/**").authenticated()
                        .anyRequest().authenticated())
                .httpBasic(withDefaults());
        return http.build();
    }
}
