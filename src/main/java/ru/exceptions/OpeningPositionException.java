package ru.exceptions;

public class OpeningPositionException extends RuntimeException {
    public OpeningPositionException(String message, Exception e) {
        super(message, e);
    }
    public OpeningPositionException(String message) {
        super(message);
    }
}
