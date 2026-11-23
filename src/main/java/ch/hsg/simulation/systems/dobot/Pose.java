package ch.hsg.simulation.systems.dobot;

/** Cartesian pose of the Dobot's end effector in mm, {@code r} is the head rotation in degrees. */
public record Pose(double x, double y, double z, double r) {

    double distanceTo(Pose other) {
        return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2) + Math.pow(z - other.z, 2));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ", " + r + ")";
    }
}
