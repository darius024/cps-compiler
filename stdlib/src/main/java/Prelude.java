public class Prelude {
    public static void println(Object message, Continuation<Void> continuation) {
        System.out.println(message);
        continuation.accept(null);
    }
}
