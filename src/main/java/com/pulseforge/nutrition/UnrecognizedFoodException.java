package com.pulseforge.nutrition;

/** The vision model could not confidently identify food in the photo (F02 / UC03 4a). */
public class UnrecognizedFoodException extends Exception {
    public UnrecognizedFoodException(String message) {
        super(message);
    }
}
