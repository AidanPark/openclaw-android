/**
 * TipCard - Reusable tip/advice card component.
 * Displays rotating tips during installation or as standalone advice.
 */

import { memo, useState, useEffect } from 'react'

interface TipCardProps {
    tips?: string[]
    interval?: number  // ms between tip rotation
    icon?: string
    className?: string
    style?: React.CSSProperties
}

export const TipCard = memo(function TipCard({
    tips = [],
    interval = 4000,
    icon = '💡',
    className = '',
    style = {},
}: TipCardProps) {
    const [currentIndex, setCurrentIndex] = useState(0)

    const rotatedTips = tips.length > 0 ? tips : [
        'Tip: OpenClaw runs entirely on-device. Your data stays private.',
        'Tip: Use the terminal for advanced git operations and package management.',
        'Tip: Enable Keep Alive to prevent Android from killing the app.',
        'Tip: Install tools like tmux for persistent terminal sessions.',
    ]

    useEffect(() => {
        if (rotatedTips.length <= 1) return

        const timer = setInterval(() => {
            setCurrentIndex(i => (i + 1) % rotatedTips.length)
        }, interval)

        return () => clearInterval(timer)
    }, [rotatedTips.length, interval])

    return (
        <div
            className={`tip-card ${className}`}
            style={{
                background: 'var(--bg-tertiary)',
                border: '1px solid var(--border)',
                borderRadius: 8,
                padding: '12px 16px',
                display: 'flex',
                alignItems: 'flex-start',
                gap: 10,
                fontSize: 13,
                color: 'var(--text-secondary)',
                transition: 'opacity 0.3s ease',
                ...style,
            }}
        >
            <span style={{ fontSize: 16, flexShrink: 0 }}>{icon}</span>
            <span style={{ lineHeight: 1.5 }}>
                {rotatedTips[currentIndex]}
            </span>
        </div>
    )
})

// Variant: Error/Warning tip card
export const WarningCard = memo(function WarningCard({
    title,
    message,
    action,
}: {
    title: string
    message: string
    action?: { label: string; onClick: () => void }
}) {
    return (
        <div
            className="tip-card"
            style={{
                background: 'var(--warning-dim)',
                border: '1px solid var(--warning)',
                borderRadius: 8,
                padding: '12px 16px',
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
            }}
        >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 16 }}>⚠️</span>
                <span style={{ fontWeight: 600, color: 'var(--warning)' }}>{title}</span>
            </div>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginLeft: 24 }}>
                {message}
            </div>
            {action && (
                <button
                    className="btn btn-sm"
                    style={{
                        alignSelf: 'flex-start',
                        marginLeft: 24,
                        marginTop: 4,
                        background: 'var(--warning)',
                        color: '#000'
                    }}
                    onClick={action.onClick}
                >
                    {action.label}
                </button>
            )}
        </div>
    )
})

// Variant: Success tip card
export const SuccessCard = memo(function SuccessCard({
    title,
    message,
}: {
    title: string
    message?: string
}) {
    return (
        <div
            className="tip-card"
            style={{
                background: 'var(--success-dim)',
                border: '1px solid var(--success)',
                borderRadius: 8,
                padding: '12px 16px',
                display: 'flex',
                flexDirection: 'column',
                gap: 4,
            }}
        >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 16 }}>✓</span>
                <span style={{ fontWeight: 600, color: 'var(--success)' }}>{title}</span>
            </div>
            {message && (
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginLeft: 24 }}>
                    {message}
                </div>
            )}
        </div>
    )
})