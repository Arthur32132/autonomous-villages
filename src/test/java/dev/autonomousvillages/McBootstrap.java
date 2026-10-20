package dev.autonomousvillages;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

/** Vanilla registries with the items' default components bound, as a server has them; safe to call from any test, any number of times. */
final class McBootstrap {
    private McBootstrap(){}
    static synchronized void init(){
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        // Stack sizes and damage live in default components, which a server binds when it loads its data.
        if(!Items.BREAD.builtInRegistryHolder().areComponentsBound())
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
    }
}
