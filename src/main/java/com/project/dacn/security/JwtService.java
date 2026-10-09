package com.project.dacn.security;

import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public String generateToken(String username) {
        // TODO: implement JWT generation
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public boolean isValid(String token, String username) {
        // TODO: implement JWT validation
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
