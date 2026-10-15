package dev.vectorcombat;

import traben.entity_model_features.EMFAnimationApi;

/**
 * Entity Model Features (and the packs that animate through it, such as Fresh Animations and its Player Extension) poses a model while it is being drawn, after
 * {@code setupAnim} and after this mod's pose: its expressions overwrite the parts they animate, so a swing, a chest twist or a mob's leg bend set before would vanish.
 * EMF calls a hook when it has animated a model; this one puts back on top what EMF overwrote ({@link ThirdPerson#afterAnimator}, {@link WindUpClient#afterAnimator}), blending
 * from EMF's pose the same way the swing blends from vanilla's. Only loaded when EMF is installed (the check is in {@code VectorCombatClient}, outside this class); the classes it names come from EMF at run time.
 */
final class EmfCompat {
    private EmfCompat() {
    }

    /** Only call when EMF is installed (the caller checks): this class names EMF's classes, so the JVM fails to verify it without them. */
    static void register() {
        try {
            EMFAnimationApi.registerAnimationHook(new EMFAnimationApi.EMFAnimationHook() {
                @Override
                public void onAnimationEnd(AnimationContext context, boolean skipped) {
                    if (skipped) return;
                    try { // a throw here would make EMF drop the model's animations
                        Object entity = EMFAnimationApi.getCurrentEntity();
                        ThirdPerson.afterAnimator(entity);
                        WindUpClient.afterAnimator(entity);
                        MobGuardClient.afterAnimator(entity);
                    } catch (RuntimeException e) {
                        ThirdPerson.fail(e);
                    }
                }
            });
            VectorCombat.LOG.info("Entity Model Features found: swings and wind-ups are re-applied after its animations");
        } catch (Throwable t) { // an EMF without this API: its animations win over ours, nothing breaks
            VectorCombat.LOG.warn("Entity Model Features found but its animation hook is not available; its animations will override swing poses", t);
        }
    }
}
