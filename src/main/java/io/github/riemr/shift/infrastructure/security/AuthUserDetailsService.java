package io.github.riemr.shift.infrastructure.security;

import io.github.riemr.shift.infrastructure.mapper.EmployeeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

@RequiredArgsConstructor
public class AuthUserDetailsService implements UserDetailsService {
    private final EmployeeMapper employeeMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AuthUser u = employeeMapper.selectAuthByEmployeeCode(username);
        if (u == null || u.getPasswordHash() == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        String role = (u.getAuthorityCode() == null || u.getAuthorityCode().isBlank()) ? "USER" : u.getAuthorityCode();
        List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new User(u.getEmployeeCode(), u.getPasswordHash(), auths);
    }
}

