package pcpulse.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AuthManager {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final SecureRandom rng = new SecureRandom();

    private String pin;
    private final Set<String> tokens = new HashSet<>();
    private final File tokensFile;

    public AuthManager() {
        // храним в ~/.pcpulse/, чтобы токены пережили перезапуск
        File dir = new File(System.getProperty("user.home"), ".pcpulse");
        if (!dir.exists()) dir.mkdirs();
        this.tokensFile = new File(dir, "tokens.json");

        loadTokens();
        regeneratePin();
    }

    public synchronized void regeneratePin() {
        this.pin = String.format("%06d", rng.nextInt(1_000_000));
    }

    public synchronized String getPin() {
        return pin;
    }

    public synchronized String pair(String inputPin) {
        if (inputPin == null || !inputPin.equals(pin)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        tokens.add(token);
        saveTokens();
        return token;
    }

    public synchronized boolean isAuthorized(String token) {
        return token != null && !token.isEmpty() && tokens.contains(token);
    }

    public synchronized void revokeAll() {
        tokens.clear();
        saveTokens();
        regeneratePin();
    }

    public synchronized int getAuthorizedCount() {
        return tokens.size();
    }

    private void loadTokens() {
        if (!tokensFile.exists()) return;
        try {
            Set<String> loaded = mapper.readValue(tokensFile, new TypeReference<Set<String>>() {});
            tokens.addAll(loaded);
        } catch (IOException e) {
            // файл мог побиться — не страшно, просто начнём с пустого списка
            System.err.println("[Auth] Не удалось прочитать tokens.json: " + e.getMessage());
        }
    }

    private void saveTokens() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tokensFile, tokens);
        } catch (IOException e) {
            System.err.println("[Auth] Запись tokens.json упала: " + e.getMessage());
        }
    }
}
