import { Bell, Settings, User } from 'lucide-react';
import './Header.css';

export const Header = () => {
    return (
        <header className="header-container">
            <div className="header-left">
                <div className="logo">
                    <div className="logo-icon">C</div>
                    CoinFlow
                </div>
                <nav>
                    <a href="#" className="nav-item active">Chart</a>
                </nav>
            </div>

            <div className="header-right">
                <button className="icon-btn" title="Notifications">
                    <Bell size={18} />
                </button>
                <button className="icon-btn" title="Settings">
                    <Settings size={18} />
                </button>
                <div className="user-avatar" title="User Profile">
                    <User size={18} />
                </div>
            </div>
        </header>
    );
};
