package com.staffhelp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StaffHelpMod implements ModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("staffhelp");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, Long> LAST_USE = new HashMap<>();

	private static Config config = new Config();

	/** Contenido de config/staffhelp.json */
	public static class Config {
		/** Segundos de espera entre pedidos de ayuda (el staff no tiene cooldown). 0 = sin cooldown. */
		public int cooldownSeconds = 30;
		/** Largo maximo del mensaje. */
		public int maxMessageLength = 200;
		/** Nivel de permiso (OP) necesario para usar /staffhelp. */
		public int adminPermissionLevel = 3;
		/** Lista de staff. */
		public List<StaffEntry> staff = new ArrayList<>();
	}

	public static class StaffEntry {
		public String uuid;
		public String name;

		public StaffEntry() {
		}

		public StaffEntry(String uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}
	}

	@Override
	public void onInitialize() {
		loadConfig();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerHelpCommand(dispatcher, "ayuda");
			registerHelpCommand(dispatcher, "sos");
			registerAdminCommand(dispatcher);
		});
	}

	// ------------------------------------------------------------------ config

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("staffhelp.json");
	}

	private static boolean loadConfig() {
		Path path = configPath();
		boolean ok = true;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				Config loaded = GSON.fromJson(reader, Config.class);
				if (loaded != null) {
					if (loaded.staff == null) {
						loaded.staff = new ArrayList<>();
					}
					config = loaded;
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.error("No se pudo leer {}. Se mantiene la configuracion anterior.", path, e);
				ok = false;
			}
		}
		if (ok) {
			saveConfig();
		}
		return ok;
	}

	private static void saveConfig() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			LOGGER.error("No se pudo guardar {}", path, e);
		}
	}

	// ------------------------------------------------------------------- staff

	private static boolean isStaff(UUID uuid) {
		String id = uuid.toString();
		for (StaffEntry entry : config.staff) {
			if (id.equalsIgnoreCase(entry.uuid)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> staffNames() {
		List<String> names = new ArrayList<>();
		for (StaffEntry entry : config.staff) {
			if (entry.name != null) {
				names.add(entry.name);
			}
		}
		return names;
	}

	// ----------------------------------------------------------- /ayuda, /sos

	private static void registerHelpCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
		dispatcher.register(Commands.literal(name)
				.executes(ctx -> {
					ctx.getSource().sendFailure(Component.literal("Uso: /" + name + " <mensaje con tu problema>"));
					return 0;
				})
				.then(Commands.argument("mensaje", StringArgumentType.greedyString())
						.executes(ctx -> sendHelp(ctx.getSource(), StringArgumentType.getString(ctx, "mensaje")))));
	}

	private static int sendHelp(CommandSourceStack source, String raw) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();

		String text = raw.replace("\u00A7", "").trim();
		if (text.isEmpty()) {
			source.sendFailure(Component.literal("El mensaje no puede estar vacio."));
			return 0;
		}
		if (text.length() > config.maxMessageLength) {
			source.sendFailure(Component.literal("Mensaje demasiado largo (maximo " + config.maxMessageLength + " caracteres)."));
			return 0;
		}

		boolean senderIsStaff = isStaff(player.getUUID());
		long now = System.currentTimeMillis();
		if (!senderIsStaff && config.cooldownSeconds > 0) {
			Long last = LAST_USE.get(player.getUUID());
			if (last != null) {
				long remaining = config.cooldownSeconds - (now - last) / 1000L;
				if (remaining > 0) {
					source.sendFailure(Component.literal("Espera " + remaining + " s antes de volver a pedir ayuda."));
					return 0;
				}
			}
		}

		List<ServerPlayer> onlineStaff = new ArrayList<>();
		for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
			if (isStaff(p.getUUID())) {
				onlineStaff.add(p);
			}
		}
		if (onlineStaff.isEmpty()) {
			source.sendFailure(Component.literal("No hay staff conectado en este momento."));
			return 0;
		}

		String senderName = player.getGameProfile().getName();

		Style tpStyle = Style.EMPTY
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tp @s " + senderName))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
						Component.literal("Teletransportarte a " + senderName)));

		MutableComponent line = Component.empty()
				.append(Component.literal("[AYUDA] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
				.append(Component.literal(senderName).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" [TP]").withStyle(tpStyle));

		for (ServerPlayer staff : onlineStaff) {
			staff.sendSystemMessage(line);
			staff.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1.0F, 1.0F);
		}

		LAST_USE.put(player.getUUID(), now);
		LOGGER.info("[AYUDA] {}: {}", senderName, text);

		int count = onlineStaff.size();
		source.sendSuccess(() -> Component
				.literal("Mensaje enviado al staff (" + count + " conectado" + (count == 1 ? "" : "s") + ").")
				.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	// ------------------------------------------------------------ /staffhelp

	private static void registerAdminCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("staffhelp")
				.requires(src -> src.hasPermission(config.adminPermissionLevel))
				.then(Commands.literal("add")
						.then(Commands.argument("jugador", GameProfileArgument.gameProfile())
								.executes(ctx -> addStaff(ctx.getSource(),
										GameProfileArgument.getGameProfiles(ctx, "jugador")))))
				.then(Commands.literal("remove")
						.then(Commands.argument("nombre", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(staffNames(), builder))
								.executes(ctx -> removeStaff(ctx.getSource(), StringArgumentType.getString(ctx, "nombre")))))
				.then(Commands.literal("list")
						.executes(ctx -> listStaff(ctx.getSource())))
				.then(Commands.literal("reload")
						.executes(ctx -> {
							boolean ok = loadConfig();
							if (ok) {
								ctx.getSource().sendSuccess(() -> Component.literal("Configuracion de StaffHelp recargada.")
										.withStyle(ChatFormatting.GREEN), true);
							} else {
								ctx.getSource().sendFailure(Component.literal("Error al leer staffhelp.json, revisa la consola."));
							}
							return ok ? 1 : 0;
						})));
	}

	private static int addStaff(CommandSourceStack source, Collection<GameProfile> profiles) {
		int added = 0;
		for (GameProfile profile : profiles) {
			String uuid = profile.getId().toString();
			boolean exists = false;
			for (StaffEntry entry : config.staff) {
				if (uuid.equalsIgnoreCase(entry.uuid)) {
					entry.name = profile.getName();
					exists = true;
					break;
				}
			}
			if (exists) {
				source.sendFailure(Component.literal(profile.getName() + " ya es staff."));
			} else {
				config.staff.add(new StaffEntry(uuid, profile.getName()));
				added++;
				String name = profile.getName();
				source.sendSuccess(() -> Component.literal(name + " ahora es staff.")
						.withStyle(ChatFormatting.GREEN), true);
			}
		}
		if (added > 0) {
			saveConfig();
		}
		return added;
	}

	private static int removeStaff(CommandSourceStack source, String name) {
		StaffEntry found = null;
		for (StaffEntry entry : config.staff) {
			if (name.equalsIgnoreCase(entry.name)) {
				found = entry;
				break;
			}
		}
		if (found == null) {
			source.sendFailure(Component.literal(name + " no esta en la lista de staff."));
			return 0;
		}
		config.staff.remove(found);
		saveConfig();
		String removedName = found.name;
		source.sendSuccess(() -> Component.literal(removedName + " ya no es staff.")
				.withStyle(ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int listStaff(CommandSourceStack source) {
		if (config.staff.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No hay staff designado. Usa /staffhelp add <jugador>."), false);
			return 0;
		}
		MutableComponent msg = Component.literal("Staff designado (" + config.staff.size() + "):")
				.withStyle(ChatFormatting.GOLD);
		for (StaffEntry entry : config.staff) {
			boolean online = false;
			try {
				online = source.getServer().getPlayerList().getPlayer(UUID.fromString(entry.uuid)) != null;
			} catch (IllegalArgumentException ignored) {
				// uuid invalido en el json
			}
			msg.append(Component.literal("\n - " + entry.name).withStyle(ChatFormatting.WHITE));
			msg.append(Component.literal(online ? " (conectado)" : " (desconectado)")
					.withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
		source.sendSuccess(() -> msg, false);
		return config.staff.size();
	}
}
