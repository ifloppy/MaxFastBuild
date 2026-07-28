package dev.maxfastbuild.fabric.mixin;

import dev.maxfastbuild.fabric.InternalCommandRouter;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerCommandInterceptorMixin {
    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void maxfastbuild$intercept(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
        if (InternalCommandRouter.handle(self.player, packet.command())) ci.cancel();
    }
}
