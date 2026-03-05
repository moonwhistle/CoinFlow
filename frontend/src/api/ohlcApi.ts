import type { OhlcChartResponse, OhlcInterval } from '../types/chart';

/**
 * Fetch OHLC data for a given symbol.
 * @param symbolId - The ID of the symbol to query (e.g., 1 for BTCUSDT)
 * @param interval - Time interval for the candles (M1, M5, M30)
 * @param candles - Number of candles to retrieve (default: 120)
 */
export const getOhlcData = async (
    symbolId: number,
    interval: OhlcInterval = 'M1',
    candles: number = 120
): Promise<OhlcChartResponse> => {
    // Construct the URL with query parameters
    const queryParams = new URLSearchParams({
        interval,
        candles: candles.toString(),
    });
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const url = `${baseUrl}/api/v1/ohlc/${symbolId}?${queryParams.toString()}`;
    console.log(`[ohlcApi] Requesting: ${url}`);

    try {
        const response = await fetch(url);

        if (!response.ok) {
            throw new Error(`API Error: ${response.status} ${response.statusText}`);
        }

        const data: OhlcChartResponse = await response.json();

        console.log(`[ohlcApi] Success. Candles: ${data.candles.length}`);
        return data;
    } catch (error) {
        console.error('Failed to fetch OHLC data:', error);
        throw error;
    }
};
