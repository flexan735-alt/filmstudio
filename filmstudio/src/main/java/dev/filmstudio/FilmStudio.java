package dev.filmstudio;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.filmstudio.data.FilmStore;
import dev.filmstudio.model.ModelActorEntity;
import dev.filmstudio.net.Net;
import dev.filmstudio.play.Playback;
import dev.filmstudio.rec.Recorder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class FilmStudio implements ModInitializer {
	public static final String MOD_ID = "filmstudio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModelActorEntity.register();
		Net.init();
		registerEvents();
		registerCommands();
		LOGGER.info("Film Studio loaded. Data folder: {}", FilmStore.root());
	}

	private static Recorder rec(net.minecraft.world.entity.player.Player p) {
		return p instanceof ServerPlayer sp && !Recorder.ACTIVE.isEmpty() ? Recorder.ACTIVE.get(sp.getUUID()) : null;
	}

	private void registerEvents() {
		ServerTickEvents.START_SERVER_TICK.register(server -> Playback.tickAll());
		ServerTickEvents.END_SERVER_TICK.register(server -> Recorder.tickAll());

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Recorder r : new ArrayList<>(Recorder.ACTIVE.values())) Recorder.stop(r.player, true);
			Playback.stopAll();
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Playback.onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.getPlayer();
			Recorder.stop(p, true);
			Playback.stopFor(p);
		});

		// Leftovers from a crash or a hard stop: remove stray model actors / puppets when their chunk loads again.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (!Playback.SESSIONS.isEmpty() || !entity.getTags().contains(Playback.PUPPET_TAG)) return;
			if (entity instanceof ModelActorEntity || entity.getTags().contains(Playback.SPAWNED_TAG)) {
				world.getServer().execute(entity::discard);
			} else if (entity instanceof Mob m) {
				m.setNoAi(false);
				m.removeTag(Playback.PUPPET_TAG);
			}
		});

		// ---- recording hooks
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			Recorder r = rec(player);
			if (r != null) r.onUseBlock(hand, hit);
			return InteractionResult.PASS;
		});
		AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
			Recorder r = rec(player);
			if (r != null) r.onDigStart(pos, dir.ordinal());
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			Recorder r = rec(player);
			if (r != null) r.onBreak(pos, state);
		});
		UseItemCallback.EVENT.register((player, world, hand) -> {
			Recorder r = rec(player);
			if (r != null) r.onUseItem(hand);
			return InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			Recorder r = rec(player);
			if (r != null) r.onAttack(entity, false, hand);
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			Recorder r = rec(player);
			if (r != null) r.onAttack(entity, true, hand);
			return InteractionResult.PASS;
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			Recorder r = rec(sender);
			if (r != null) r.onChat(message.signedContent());
		});
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("film")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (!Net.allowed(p)) {
						ctx.getSource().sendSystemMessage(Component.literal("\u00a7cYou are not allowed to use Film Studio. Ask the server console to run /film allow " + p.getGameProfile().name()));
						return 0;
					}
					Net.send(p, "open", "{}", new byte[0]);
					Net.sendFilms(p);
					return 1;
				})
				.then(Commands.literal("stop").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Recorder.stop(p, true);
					Playback.stopFor(p);
					Net.sendIdle(p);
					return 1;
				}))
				.then(Commands.literal("allow")
					.requires(src -> src.getEntity() == null)
					.then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "player");
						FilmStore.allow(name);
						ctx.getSource().sendSystemMessage(Component.literal("Film Studio access granted to " + name));
						return 1;
					}))));
		});
	}
}
