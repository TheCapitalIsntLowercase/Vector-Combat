package traben.entity_model_features;

import traben.entity_model_features.utils.EMFEntity;

/**
 * Compile-time stand-in for the part of Entity Model Features' API that {@code dev.vectorcombat.EmfCompat} calls (EMF 3.x, API version 11). Only on the build's classpath, never in
 * the jar: at run time the real class answers, and only when EMF is installed. Names and descriptors must match EMF's exactly.
 */
public interface EMFAnimationApi {
    static EMFEntity getCurrentEntity() {
        throw new AssertionError("stub");
    }

    static boolean registerAnimationHook(EMFAnimationHook hook) throws Exception {
        throw new AssertionError("stub");
    }

    abstract class EMFAnimationHook {
        public void onAnimationEnd(AnimationContext context, boolean skipped) {
        }

        public static final class AnimationContext {
            private AnimationContext() {
            }
        }
    }
}
