package de.mrjulsen.paw.traction;

public record ElectricTrainSnapshot(
    boolean capable,
    boolean powered,
    long lastCapabilityTick,
    long lastContactTick
) {}
