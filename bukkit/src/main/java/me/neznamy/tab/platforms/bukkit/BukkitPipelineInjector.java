package me.neznamy.tab.platforms.bukkit;

import java.util.ArrayList;
import java.util.Collection;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import me.neznamy.tab.api.TabFeature;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.platforms.bukkit.nms.NMSStorage;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.features.PipelineInjector;

public class BukkitPipelineInjector extends PipelineInjector {

	//nms storage
	private NMSStorage nms;

	/**
	 * Constructs new instance with given parameters
	 * @param tab - tab instance
	 * @param nms - nms storage
	 */
	public BukkitPipelineInjector(NMSStorage nms){
		super("packet_handler");
		this.nms = nms;
		channelFunction = (player) -> new BukkitChannelDuplexHandler(player);
	}

	/**
	 * Custom channel duplex handler override
	 */
	public class BukkitChannelDuplexHandler extends ChannelDuplexHandler {
		
		//injected player
		private TabPlayer player;
		
		/**
		 * Constructs new instance with given player
		 * @param player - player to inject
		 */
		public BukkitChannelDuplexHandler(TabPlayer player) {
			this.player = player;
		}

		@Override
		public void channelRead(ChannelHandlerContext context, Object packet) {
			try {
				if (TAB.getInstance().getFeatureManager().onPacketReceive(player, packet)) return;
				super.channelRead(context, packet);
			} catch (Exception e){
				TAB.getInstance().getErrorManager().printError("An error occurred when reading packets", e);
			}
		}

		@Override
		public void write(ChannelHandlerContext context, Object packet, ChannelPromise channelPromise) {
			try {
				if (nms.getClass("PacketPlayOutPlayerInfo").isInstance(packet)) {
					super.write(context, TAB.getInstance().getFeatureManager().onPacketPlayOutPlayerInfo(player, packet), channelPromise);
					return;
				}
				if (antiOverrideTeams && nms.getClass("PacketPlayOutScoreboardTeam").isInstance(packet)) {
					modifyPlayers(packet);
					super.write(context, packet, channelPromise);
					return;
				}
				if (nms.getClass("PacketPlayOutScoreboardDisplayObjective").isInstance(packet) && TAB.getInstance().getFeatureManager().onDisplayObjective(player, packet)) {
					return;
				}
				if (nms.getClass("PacketPlayOutScoreboardObjective").isInstance(packet)) {
					TAB.getInstance().getFeatureManager().onObjective(player, packet);
				}
				TAB.getInstance().getFeatureManager().onPacketSend(player, packet);
			} catch (Exception | NoClassDefFoundError e){
				TAB.getInstance().getErrorManager().printError("An error occurred when reading packets", e);
			}
			try {
				super.write(context, packet, channelPromise);
			} catch (Exception | NoClassDefFoundError e) {
				TAB.getInstance().getErrorManager().printError("Failed to forward packet " + packet.getClass().getSimpleName() + " to " + player.getName(), e);
			}
		}
		
		/**
		 * Removes all real players from team if packet does not come from TAB and reports this to override log
		 * @param packetPlayOutScoreboardTeam - team packet
		 * @throws IllegalAccessException 
		 */
		@SuppressWarnings("unchecked")
		private void modifyPlayers(Object packetPlayOutScoreboardTeam) throws IllegalAccessException {
			long time = System.nanoTime();
			Collection<String> players = (Collection<String>) nms.getField("PacketPlayOutScoreboardTeam_PLAYERS").get(packetPlayOutScoreboardTeam);
			String teamName = (String) nms.getField("PacketPlayOutScoreboardTeam_NAME").get(packetPlayOutScoreboardTeam);
			if (players == null) return;
			//creating a new list to prevent NoSuchFieldException in minecraft packet encoder when a player is removed
			Collection<String> newList = new ArrayList<>();
			for (String entry : players) {
				TabPlayer p = TAB.getInstance().getPlayer(entry);
				if (p == null) {
					newList.add(entry);
					continue;
				}
				if (!((TabFeature)TAB.getInstance().getTeamManager()).getDisabledPlayers().contains(p) && 
						!TAB.getInstance().getTeamManager().hasTeamHandlingPaused(p) && !teamName.equals(p.getTeamName())) {
					logTeamOverride(teamName, entry);
				} else {
					newList.add(entry);
				}
			}
			nms.setField(packetPlayOutScoreboardTeam, "PacketPlayOutScoreboardTeam_PLAYERS", newList);
			TAB.getInstance().getCPUManager().addTime("Nametags", UsageType.ANTI_OVERRIDE, System.nanoTime()-time);
		}
	}
}