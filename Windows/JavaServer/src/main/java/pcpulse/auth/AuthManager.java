package pcpulse.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages PIN-code generation and token-based authentication.
 * 
 * Flow:
 * 1. On startup, a random 6-digit PIN is generated and displayed in the GUI.
 * 2. A mobile client sends the PIN via POST /auth/pair.
 * 3. If the PIN is correct, a UUID token is issued and stored persistently.
 * 4. All subsequent connections use the token for authentication.
 * 5. Tokens are saved to a JSON file so they survive agent restarts.
 */
public class AuthManager {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final SecureRandom random = new SecureRandom();

    private String currentPin;
    private final Set<String> authorizedTokens = new HashSet<>();
    private final File tokensFile;

    public AuthManager() {
        // Store tokens in user home directory under .pcpulse/
        String userHome = System.getProperty("user.home");
        File dir = new File(userHome, ".pcpulse");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.tokensFile = new File(dir, "tokens.json");

        loadTokens();
        regeneratePin();
    }

    /**
     * Generates a new random 6-digit PIN code.
     */
    public synchronized void regeneratePin() {
        this.currentPin = String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * Returns the current PIN code (for display in the GUI).
     */
    public synchronized String getPin() {
        return currentPin;
    }

    /**
     * Attempts to pair a client using the provided PIN.
     * If the PIN matches, generates and stores a new token.
     *
     * @param pin the PIN entered by the client
     * @return the generated token if PIN is correct, or null if incorrect
     */
    public synchronized String pair(String pin) {
        if (pin == null || !pin.equals(currentPin)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        authorizedTokens.add(token);
        saveTokens();
        return token;
    }

    /**
     * Checks whether the given token is authorized.
     */
    public synchronized boolean isAuthorized(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        return authorizedTokens.contains(token);
    }

    /**
     * Revokes all authorized tokens (e.g. when the user presses "Reset Devices").
     */
    public synchronized void revokeAll() {
        authorizedTokens.clear();
        saveTokens();
    }

    /**
     * Returns the number of currently authorized tokens/devices.
     */
    public synchronized int getAuthorizedCount() {
        return authorizedTokens.size();
    }

    private void loadTokens() {
        if (tokensFile.exists()) {
            try {
                Set<String> loaded = mapper.readValue(tokensFile, new TypeReference<Set<String>>() {});
                authorizedTokens.addAll(loaded);
            } catch (IOException e) {
                System.err.println("[AuthManager] Failed to load tokens: " + e.getMessage());
            }
        }
    }

    private void saveTokens() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tokensFile, authorizedTokens);
        } catch (IOException e) {
            System.err.println("[AuthManager] Failed to save tokens: " + e.getMessage());
        }
    }
}
