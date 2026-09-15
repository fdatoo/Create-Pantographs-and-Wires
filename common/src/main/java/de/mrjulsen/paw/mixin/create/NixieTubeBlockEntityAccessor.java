package de.mrjulsen.paw.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.foundation.utility.Couple;

/**
 * Sets the two glyphs a nixie tube draws. Used on the copy of a tube riding on a train, where going
 * through the tube's own setters would notify display links and send block updates into the wrong world.
 */
@Mixin(value = NixieTubeBlockEntity.class, remap = false)
public interface NixieTubeBlockEntityAccessor {
    @Accessor("displayedStrings")
    void paw$setDisplayedStrings(Couple<String> strings);
}
