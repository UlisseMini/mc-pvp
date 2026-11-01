package practice.api;

/** Local module capabilities. Networking uses the current localhost game connection. */
public interface PracticeServices {
    void marker(String label);
    /** Supported v1 messages: marker and ping. No commands or code execution. */
    boolean send(String type,String value);
    void sound(String id,float volume,float pitch);
}
