package datasource

import (
	"linplayer/core/bus"
	"linplayer/core/emby"
	"linplayer/core/sourcecmd"
)

// RegisterCommands 由 core/commands 调用。数据源命令都在 source.* 下(和文件浏览型源同一个域)。
func RegisterCommands(version string) {
	embyClient = emby.NewClient(version)
	sourcecmd.PluginForms = pluginForms
	register()
	bus.Register("source.createSources", cmdCreateSources)
	bus.Register("source.addSources", cmdAddSources)
	bus.Register("source.setAggregate", cmdSetAggregate)
	bus.Register("source.setHost", cmdSetHost)
	bus.Register("source.removeGroup", cmdRemoveGroup)
	bus.Register("source.serverMenus", cmdServerMenus)
	bus.Register("source.runCommand", cmdRunCommand)
	bus.Register("source.aggregateSearch", cmdAggregateSearch)
	bus.Register("source.switchCandidates", cmdSwitchCandidates)
	bus.Register("source.checkAll", cmdCheckAll)
	bus.Register("source.setFavorite", cmdSetFavorite)
	bus.Register("source.isFavorite", cmdIsFavorite)
	bus.Register("source.favorites", cmdFavorites)
	bus.Register("source.history", cmdHistory)
	bus.Register("source.linkSwitch", cmdLinkSwitch)
}
