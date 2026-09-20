// 纯主题包没有可执行逻辑:内容全在 theme/ 下,由壳直接读(SPEC 11.4)。
// 入口只是为了满足「每个包都有入口」这条约定。
const { definePlugin } = __linplayer_sdk;
definePlugin({});
