public interface Continuation<T> {
    void accept(T t);
}
