import type { LlmProvider, ProviderAdapter, ProviderConfig } from "./contracts.ts";
import { ProviderError } from "./errors.ts";
import { openAIChatAdapter } from "./openai-chat.ts";
import { parseConfig } from "./validation.ts";

/** Protocol implementations and configured connections have separate identities. */
export class ProviderRegistry {
  private readonly adapters = new Map<string, ProviderAdapter>();
  private readonly providers = new Map<string, LlmProvider>();

  constructor(adapters: ProviderAdapter[] = [openAIChatAdapter]) {
    for (const adapter of adapters) this.registerAdapter(adapter);
  }

  registerAdapter(adapter: ProviderAdapter): void {
    if (!adapter.protocol.trim() || this.adapters.has(adapter.protocol)) {
      throw new ProviderError("configuration", "registry", "Protocol adapter is invalid or already registered.");
    }
    this.adapters.set(adapter.protocol, adapter);
  }

  add(input: ProviderConfig): LlmProvider {
    const config = parseConfig(input);
    if (this.providers.has(config.id)) throw new ProviderError("configuration", config.id, "Provider ID is already registered.");
    const adapter = this.adapters.get(config.protocol);
    if (!adapter) throw new ProviderError("unsupported-protocol", config.id, "Provider protocol is not registered.");
    const provider = adapter.create(config);
    if (provider.id !== config.id || provider.protocol !== config.protocol) {
      throw new ProviderError("configuration", config.id, "Protocol adapter returned an inconsistent provider identity.");
    }
    this.providers.set(config.id, provider);
    return provider;
  }

  get(id: string): LlmProvider {
    const provider = this.providers.get(id);
    if (!provider) throw new ProviderError("configuration", id, "Provider is not registered.");
    return provider;
  }

  /** Only public identities, never connection settings or credentials. */
  list(): { id: string; protocol: string }[] {
    return [...this.providers.values()].map(({ id, protocol }) => ({ id, protocol }));
  }
}
