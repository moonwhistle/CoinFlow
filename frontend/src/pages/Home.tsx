import React from 'react';
import { APP_CONFIG, HOME_UI } from '@/constants/ui';

export const Home: React.FC = () => {
    return (
        <div className="min-h-screen bg-gray-900 text-white flex flex-col items-center justify-center">
            <header className="w-full p-4 border-b border-gray-800 flex justify-between items-center bg-gray-900 sticky top-0 z-10">
                <h1 className="text-2xl font-bold bg-gradient-to-r from-yellow-400 to-orange-500 bg-clip-text text-transparent">
                    {APP_CONFIG.TITLE}
                </h1>
                <div className="text-sm text-gray-400">
                    {HOME_UI.HEADER.STATE_LABEL} <span className="text-green-500">{HOME_UI.HEADER.STATE_CONNECTED}</span>
                </div>
            </header>

            <main className="flex-1 w-full max-w-7xl p-4 flex flex-col gap-4">
                {/* Chart Container Placeholder */}
                <div className="w-full h-[600px] bg-gray-800 rounded-lg border border-gray-700 relative overflow-hidden flex items-center justify-center">
                    <div className="text-center">
                        <p className="text-xl font-medium text-gray-300">{HOME_UI.CHART.TITLE}</p>
                        <p className="text-sm text-gray-500 mt-2">{HOME_UI.CHART.LOADING_MESSAGE}</p>
                    </div>
                </div>

                {/* Info / Stats Placeholder */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div className="bg-gray-800 p-4 rounded-lg border border-gray-700">
                        <h3 className="text-gray-400 text-sm">{HOME_UI.STATS.PRICE_LABEL}</h3>
                        <p className="text-2xl font-mono mt-1">$94,000.00</p>
                    </div>
                    <div className="bg-gray-800 p-4 rounded-lg border border-gray-700">
                        <h3 className="text-gray-400 text-sm">{HOME_UI.STATS.CHANGE_LABEL}</h3>
                        <p className="text-2xl font-mono mt-1 text-green-500">+5.2%</p>
                    </div>
                    <div className="bg-gray-800 p-4 rounded-lg border border-gray-700">
                        <h3 className="text-gray-400 text-sm">{HOME_UI.STATS.VOLUME_LABEL}</h3>
                        <p className="text-2xl font-mono mt-1">1,234 BTC</p>
                    </div>
                </div>
            </main>
        </div>
    );
};

