/**
 * Claude ↔ pi 指挥桥。
 *
 * 监听 `.pi/inbox/` 目录，把投进来的 `.md` 文件作为用户消息注入本会话——
 * 让另一个 agent（规划侧的 Claude Code）能给本会话派任务、发纠偏，而不必
 * 抢键盘焦点去模拟按键。这是 pi 官方 examples/extensions/file-trigger.ts
 * 的定制版，改动点见下。
 *
 * 文件名约定：
 *   steer-*.md  → steering 消息，当前助手轮结束后即插入（用于对进行中的工作纠偏）
 *   其余 *.md    → followUp 消息，全部工作完成后才送达（用于派下一个任务）
 *
 * 内容以 "/" 开头时按提示词模板展开——可以直接投 `/opsx-apply <变更名>`。
 *
 * 投递方约定（写入方遵守，本文件不强制）：先写 `*.tmp` 再改名为 `*.md`，
 * 避免监听器读到写了一半的文件；本扩展只认 `.md` 后缀。
 */
import * as fs from "node:fs";
import * as path from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
	let watching = false;

	const ensureWatcher = (notify?: (message: string) => void) => {
		// 幂等：session_start 与兜底事件都会调进来，只有第一次真正生效。
		if (watching) return;
		const inbox = path.join(process.cwd(), ".pi", "inbox");
		fs.mkdirSync(inbox, { recursive: true });

		/**
		 * 投递留痕：往 .pi/outbox/events.ndjson 追加一条 queued 事件，
		 * 监控台据此显示「谁投了什么、以什么方式送达」。文件名前缀即来源
		 * （claude-* 规划侧脚本 / console-* 监控台发送框）。
		 */
		const logQueued = (file: string, text: string, steer: boolean) => {
			try {
				const outbox = path.join(process.cwd(), ".pi", "outbox");
				fs.mkdirSync(outbox, { recursive: true });
				fs.appendFileSync(
					path.join(outbox, "events.ndjson"),
					`${JSON.stringify({
						ts: new Date().toISOString(),
						type: "queued",
						name: file,
						deliverAs: steer ? "steer" : "followUp",
						text: text.slice(0, 4000),
					})}\n`,
					"utf-8",
				);
			} catch {
				// 留痕失败不影响投递
			}
		};

		const deliver = (file: string) => {
			if (!file.endsWith(".md")) return; // 忽略 .tmp 等写入中间态
			const full = path.join(inbox, file);
			let text = "";
			try {
				text = fs.readFileSync(full, "utf-8").trim();
			} catch {
				return; // 已被处理，或写入尚未完成
			}
			if (!text) return;
			// 先删后投：fs.watch 对同一个文件常常连发多个事件，谁删除成功谁投递，
			// 删除失败说明另一个回调已经处理过——这是这里唯一的去重手段。
			try {
				fs.unlinkSync(full);
			} catch {
				return;
			}
			const steer = path.basename(file).startsWith("steer-");
			logQueued(path.basename(file), text, steer);
			void pi.sendUserMessage(text, {
				deliverAs: steer ? "steer" : "followUp",
				// 只有形如 /opsx-apply 的指令需要模板展开；普通文本展开反而有
				// 误触发模板的风险。
				expandPromptTemplates: text.startsWith("/"),
			});
		};

		// 先清存量：桥未上线期间投进来的任务不能丢。
		for (const existing of fs.readdirSync(inbox)) deliver(existing);
		fs.watch(inbox, (_event, file) => {
			if (file) deliver(file);
		});
		watching = true;
		notify?.(`Claude 指挥桥已就绪：监听 ${inbox}`);
	};

	pi.on("session_start", async (_event, ctx) => {
		ensureWatcher(ctx.hasUI ? (m) => ctx.ui.notify(m, "info") : undefined);
	});
	// 兜底：若 /reload 不重放 session_start，也能在下一次代理开始工作时挂上。
	// 未知事件名不会报错、只是不触发，所以两个都挂是安全的。
	pi.on("agent_start", async () => ensureWatcher());
	pi.on("agent_end", async () => ensureWatcher());
}
