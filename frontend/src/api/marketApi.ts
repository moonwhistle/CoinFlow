import type { MarketStats24h } from '../types/market';

export const getMarketStats24h = async (symbolId: number): Promise<MarketStats24h | null> => {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const response = await fetch(`${baseUrl}/api/v1/market/${symbolId}/stats/24h`);

    if (response.status === 204) {
        return null;
    }

    if (!response.ok) {
        throw new Error(`API Error: ${response.status} ${response.statusText}`);
    }

    return response.json() as Promise<MarketStats24h>;
};
