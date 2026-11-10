package com.raildispatch.control;

import com.raildispatch.api.TrackNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * How far along a granted route a vehicle has got, and what it should aim at next.
 *
 * <p>The route is Create's own representation: an ordered sequence of track nodes. Following it means
 * three things the destination alone cannot give:
 *
 * <ul>
 *   <li><b>the next node</b>, which is always on the rails - aiming at a station block beside the track,
 *       or at the far end of a loop, is how a driver ends up cutting corners;</li>
 *   <li><b>the remaining arc length</b>, summed along the nodes rather than measured as a straight line,
 *       which is what makes a stopping curve correct through a curve or a reversal;</li>
 *   <li><b>the next turn</b>, so the approach to a junction is slowed before it, not after.</li>
 * </ul>
 *
 * <p>Progress is monotonic: the index only ever advances. A nearest-node search would jump backwards at
 * every switch frog and ask the vehicle to return to a node it has already passed.
 */
public final class RouteProgress {

    /** A node counts as reached this close to it, in the horizontal plane. */
    private static final double NODE_REACHED = 2.5D;

    private final List<Vec3> points;
    /** Distance from {@code points[i]} to the end of the route, along the route. */
    private final double[] toEnd;

    private int index;

    public RouteProgress(TrackNetwork.PlannedRoute route) {
        List<Vec3> collected = new ArrayList<>(route.waypoints());
        // The destination is the last point. For a station it is the station's marker on the track, so
        // the final leg is short; for an address plate it is the plate, which stands beside the rails -
        // still the honest thing to aim at, and the arrival test uses the same point.
        if (route.to() != null) {
            if (collected.isEmpty() || collected.get(collected.size() - 1).distanceToSqr(route.to()) > 1.0E-4D) {
                collected.add(route.to());
            }
        }
        this.points = List.copyOf(collected);
        this.toEnd = new double[points.size()];
        for (int i = points.size() - 2; i >= 0; i--) {
            toEnd[i] = toEnd[i + 1] + horizontalDistance(points.get(i), points.get(i + 1));
        }
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int index() {
        return index;
    }

    public int size() {
        return points.size();
    }

    /** The node to aim at now, or {@code null} when the route has no points at all. */
    public Vec3 nextPoint() {
        return points.isEmpty() ? null : points.get(Math.min(index, points.size() - 1));
    }

    /** The destination end of the route, or {@code null} when the route has no points at all. */
    public Vec3 endPoint() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    /** The world direction from {@code from} to the next node, horizontally, or null when it is degenerate. */
    public Vec3 legDirection(Vec3 from) {
        Vec3 next = nextPoint();
        if (next == null) {
            return null;
        }
        Vec3 delta = new Vec3(next.x - from.x, 0.0D, next.z - from.z);
        if (delta.lengthSqr() > 1.0E-6D) {
            return delta.normalize();
        }
        // Standing on the node: use the outgoing leg instead, which is the direction the route continues.
        if (index + 1 < points.size()) {
            Vec3 forward = new Vec3(points.get(index + 1).x - next.x, 0.0D,
                    points.get(index + 1).z - next.z);
            if (forward.lengthSqr() > 1.0E-6D) {
                return forward.normalize();
            }
        }
        return null;
    }

    /** Arc length still to travel from {@code position}, in blocks. */
    public double remaining(Vec3 position) {
        Vec3 next = nextPoint();
        if (next == null) {
            return 0.0D;
        }
        return horizontalDistance(position, next) + toEnd[Math.min(index, toEnd.length - 1)];
    }

    /** How sharply the route turns at the next node, in degrees; 0 when that cannot be measured. */
    public double turnAngle(Vec3 position) {
        Vec3 next = nextPoint();
        if (next == null || index + 1 >= points.size()) {
            return 0.0D;
        }
        Vec3 previous = index > 0 ? points.get(index - 1) : position;
        Vec3 incoming = new Vec3(next.x - previous.x, 0.0D, next.z - previous.z);
        Vec3 outgoing = new Vec3(points.get(index + 1).x - next.x, 0.0D, points.get(index + 1).z - next.z);
        if (incoming.lengthSqr() < 1.0E-6D || outgoing.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        double cosine = incoming.normalize().dot(outgoing.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine))));
    }

    /** Advances the index past every node the vehicle has already gone by. */
    public void update(Vec3 position) {
        while (index < points.size() - 1) {
            Vec3 a = points.get(index);
            Vec3 b = points.get(index + 1);
            double here = horizontalDistance(position, a);
            double next = horizontalDistance(position, b);
            // The vehicle lying on the segment a->b means it has already left a behind: the node to aim
            // at is b. Without this, a plan that starts with the near end of the edge the vehicle stands
            // on would keep aiming back at that near end until it happened to be closer to b.
            boolean onSegment = false;
            double abx = b.x - a.x;
            double abz = b.z - a.z;
            double lengthSqr = abx * abx + abz * abz;
            if (lengthSqr > 1.0E-6D) {
                double t = ((position.x - a.x) * abx + (position.z - a.z) * abz) / lengthSqr;
                onSegment = t > 0.0D && t < 1.0D;
            }
            if (here <= NODE_REACHED || next < here || onSegment) {
                index++;
            } else {
                break;
            }
        }
    }

    /** True when the vehicle is at the end of the route within {@code radius} blocks, horizontally. */
    public boolean atEnd(Vec3 position, double radius) {
        if (points.isEmpty()) {
            return false;
        }
        Vec3 end = points.get(points.size() - 1);
        return horizontalDistance(position, end) <= radius;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
