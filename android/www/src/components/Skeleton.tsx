/**
 * Skeleton - Loading state component for better UX.
 * Displays placeholder content while data is loading.
 */

import { memo } from 'react'

interface SkeletonProps {
    width?: string | number
    height?: string | number
    borderRadius?: string | number
    style?: React.CSSProperties
    className?: string
}

export const Skeleton = memo(function Skeleton({
    width = '100%',
    height = 20,
    borderRadius = 4,
    style = {},
    className = '',
}: SkeletonProps) {
    return (
        <div
            className={`skeleton ${className}`}
            style={{
                width: typeof width === 'number' ? `${width}px` : width,
                height: typeof height === 'number' ? `${height}px` : height,
                borderRadius: typeof borderRadius === 'number' ? `${borderRadius}px` : borderRadius,
                background: 'var(--bg-tertiary)',
                animation: 'skeleton-pulse 1.5s ease-in-out infinite',
                ...style,
            }}
        />
    )
})

// Common skeleton patterns
interface SkeletonCardProps {
    lines?: number
    showAvatar?: boolean
}

export const SkeletonCard = memo(function SkeletonCard({
    lines = 3,
    showAvatar = true
}: SkeletonCardProps) {
    return (
        <div className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                {showAvatar && (
                    <Skeleton width={40} height={40} borderRadius={8} />
                )}
                <div style={{ flex: 1 }}>
                    <Skeleton width="60%" height={16} style={{ marginBottom: 8 }} />
                    {Array.from({ length: lines - 1 }).map((_, i) => (
                        <Skeleton
                            key={i}
                            width={i === lines - 2 ? '80%' : '100%'}
                            height={12}
                            style={{ marginBottom: 6 }}
                        />
                    ))}
                </div>
            </div>
        </div>
    )
})

export const SkeletonList = memo(function SkeletonList({
    count = 5,
    cardLines = 2
}: {
    count?: number
    cardLines?: number
}) {
    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {Array.from({ length: count }).map((_, i) => (
                <SkeletonCard key={i} lines={cardLines} />
            ))}
        </div>
    )
})

export const SkeletonGrid = memo(function SkeletonGrid({
    columns = 4,
    rows = 1
}: {
    columns?: number
    rows?: number
}) {
    return (
        <div style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${columns}, 1fr)`,
            gap: 12
        }}>
            {Array.from({ length: columns * rows }).map((_, i) => (
                <div key={i} style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 8,
                    padding: 12,
                }}>
                    <Skeleton width={32} height={32} borderRadius={50} />
                    <Skeleton width="70%" height={12} />
                    <Skeleton width="50%" height={10} />
                </div>
            ))}
        </div>
    )
})

// Add CSS animation if not already present
const style = document.createElement('style')
style.textContent = `
  @keyframes skeleton-pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.5; }
  }
`
document.head.appendChild(style)