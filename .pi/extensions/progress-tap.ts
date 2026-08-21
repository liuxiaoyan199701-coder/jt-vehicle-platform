/**
 * 进度外送（progress tap）。
 *
 * 把本会话的完整干活过程写成**结构化事件流**（NDJSON，一行一个 JSON）到
 * `.pi/outbox/events.ndjson`，供会话之外的消费方使用：
 *   - pi-bridge 监控台（本地网页）把它渲染成聊天窗口式的对话流
 *   - 规划侧 Claude 的里程碑监听（grep "type":"settled"）
 *
 * 事件类型：
 *   session   会话启动（含 /reload）
 *   start     开始处理一条消息
 *   user      一条用户消息真正进入对话（键盘输入或经桥投递，见 claude-bridge 的 queued）
 *   assistant 一轮助手文字（turn_end 粒度，不做流式——文件是追加型介质）
 *   tool      一次工具调用（名称 + 参数摘要）
 *   tool_error 工具执行失败
 *   settled   全部工作完成，pi 不会再自己动（对外的主要里程碑）
 *
 * 每行自带 ts。文本按上限截断——事件流是监控介质，不是会话存档，
 * 完整内容永远以 pi 自己的会话 JSONL 为准。
 */
import * as fs from "node:fs";
import * as path from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
	const outbox = path.join(process.cwd(), ".pi", "outbox");
	const eventsFile = path.join(outbox, "events.ndjson");
	const MAX_BYTES = 5 * 1024 * 1024;
	const ASSISTANT_CAP = 8000;
	const USER_CAP = 4000;

	const emit = (event: Record<string, unknown>) => {
		try {
			fs.mkdirSync(outbox, { recursive: true });
			fs.appendFileSync(
				eventsFile,
				`${JSON.stringify({ ts: new Date().toISOString(), ...event })}\n`,
				"utf-8",
			);
		} catch {
			// 外送绝不能影响干活本身：写不进去就算了。
		}
	};

	const cap = (text: string, max: number) =>
		text.length > max ? `${text.slice(0, max)}\n…（已截断，完整内容见会话记录）` : text;

	/** 工具参数摘要：bash 给命令、文件类给路径、其余给截断的 JSON。 */
	const summarize = (args: unknown): string => {
		if (!args || typeof args !== "object") return "";
		const record = args as Record<string, unknown>;
		const flat = (v: string) => v.replace(/\s+/g, " ").trim().slice(0, 160);
		if (typeof record.command === "string") return flat(record.command);
		if (typeof record.path === "string") return flat(record.path);
		if (typeof record.file_path === "string") return flat(record.file_path);
		try {
			return flat(JSON.stringify(record));
		} catch {
			return "";
		}
	};

	/** 从消息里抽纯文本（内容可能是字符串或分块数组）。 */
	const textOf = (message: unknown): string => {
		const content = (message as { content?: unknown } | undefined)?.content;
		if (typeof content === "string") return content;
		if (Array.isArray(content)) {
			return content
				.filter(
					(block): block is { type: string; text: string } =>
						!!block && (block as { type?: string }).type === "text"
						&& typeof (block as { text?: unknown }).text === "string",
				)
				.map((block) => block.text)
				.join("\n");
		}
		return "";
	};

	pi.on("session_start", async () => {
		// 简单轮转：超限就挪开，消费方检测到文件变小会自动从头重读。
		try {
			if (fs.existsSync(eventsFile) && fs.statSync(eventsFile).size > MAX_BYTES) {
				fs.renameSync(eventsFile, `${eventsFile}.old`);
			}
		} catch {
			// 轮转失败不致命
		}
		emit({ type: "session" });
	});

	pi.on("agent_start", async () => emit({ type: "start" }));

	// 用户消息在真正进入对话时记录（键盘输入与经桥投递的都会走到这里）。
	// 助手消息不在这里记——turn_end 已按轮记录，双份只会让监控台重复显示。
	pi.on("message_end", async (event) => {
		const role = (event.message as { role?: string } | undefined)?.role;
		if (role !== "user") return;
		const text = textOf(event.message);
		if (text) emit({ type: "user", text: cap(text, USER_CAP) });
	});

	pi.on("tool_execution_start", async (event) => {
		emit({ type: "tool", name: event.toolName, summary: summarize(event.args) });
	});

	pi.on("tool_execution_end", async (event) => {
		if (event.isError) {
			emit({ type: "tool_error", name: event.toolName });
		}
	});

	pi.on("turn_end", async (event) => {
		const text = textOf(event.message);
		if (text) emit({ type: "assistant", turn: event.turnIndex, text: cap(text, ASSISTANT_CAP) });
	});

	// agent_settled 才是「不会再自己动了」：agent_end 之后仍可能自动重试、
	// 压缩上下文续跑、或处理排队的 followUp 消息。
	pi.on("agent_settled", async () => emit({ type: "settled" }));
}
