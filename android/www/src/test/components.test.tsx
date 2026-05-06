import { describe, it, expect, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TipCard, WarningCard, SuccessCard } from '../components/TipCard'
import { Skeleton, SkeletonCard, SkeletonGrid } from '../components/Skeleton'

// ── TipCard ────────────────────────────────────────────────────────────────

describe('TipCard', () => {
    it('renders with default tips when none provided', () => {
        render(<TipCard />)
        expect(screen.getByText(/OpenClaw runs entirely on-device/)).toBeInTheDocument()
    })

    it('renders custom tips', () => {
        render(<TipCard tips={['Custom tip 1', 'Custom tip 2']} />)
        expect(screen.getByText('Custom tip 1')).toBeInTheDocument()
    })

    it('renders custom icon', () => {
        render(<TipCard icon="🚀" tips={['tip']} />)
        expect(screen.getByText('🚀')).toBeInTheDocument()
    })

    it('applies custom className', () => {
        const { container } = render(<TipCard className="my-class" tips={['tip']} />)
        expect(container.firstChild).toHaveClass('my-class')
    })

    it('rotates tips on interval', async () => {
        vi.useFakeTimers()
        render(<TipCard tips={['Tip A', 'Tip B', 'Tip C']} interval={1000} />)
        expect(screen.getByText('Tip A')).toBeInTheDocument()

        await act(async () => { vi.advanceTimersByTime(1000) })
        expect(screen.getByText('Tip B')).toBeInTheDocument()

        await act(async () => { vi.advanceTimersByTime(1000) })
        expect(screen.getByText('Tip C')).toBeInTheDocument()

        // Wraps around
        await act(async () => { vi.advanceTimersByTime(1000) })
        expect(screen.getByText('Tip A')).toBeInTheDocument()

        vi.useRealTimers()
    })

    it('does not rotate when only one tip', async () => {
        vi.useFakeTimers()
        render(<TipCard tips={['Only tip']} interval={500} />)
        await act(async () => { vi.advanceTimersByTime(2000) })
        expect(screen.getByText('Only tip')).toBeInTheDocument()
        vi.useRealTimers()
    })
})

describe('WarningCard', () => {
    it('renders title and message', () => {
        render(<WarningCard title="Warning!" message="Something went wrong" />)
        expect(screen.getByText('Warning!')).toBeInTheDocument()
        expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })

    it('renders action button when provided', async () => {
        const onClick = vi.fn()
        render(<WarningCard title="Warn" message="msg" action={{ label: 'Fix it', onClick }} />)
        const btn = screen.getByText('Fix it')
        expect(btn).toBeInTheDocument()
        await userEvent.click(btn)
        expect(onClick).toHaveBeenCalledOnce()
    })

    it('does not render button when action is absent', () => {
        render(<WarningCard title="Warn" message="msg" />)
        expect(screen.queryByRole('button')).toBeNull()
    })
})

describe('SuccessCard', () => {
    it('renders title', () => {
        render(<SuccessCard title="All good!" />)
        expect(screen.getByText('All good!')).toBeInTheDocument()
    })

    it('renders optional message', () => {
        render(<SuccessCard title="Done" message="Everything installed" />)
        expect(screen.getByText('Everything installed')).toBeInTheDocument()
    })

    it('does not render message element when absent', () => {
        const { container } = render(<SuccessCard title="Done" />)
        // Only the title div should be present, no extra text
        expect(container.querySelectorAll('div').length).toBe(2) // outer + title row
    })
})

// ── Skeleton ───────────────────────────────────────────────────────────────

describe('Skeleton', () => {
    it('renders with default dimensions', () => {
        const { container } = render(<Skeleton />)
        const el = container.firstChild as HTMLElement
        expect(el).toBeInTheDocument()
        expect(el.style.height).toBe('20px')
    })

    it('accepts numeric width and height', () => {
        const { container } = render(<Skeleton width={100} height={50} />)
        const el = container.firstChild as HTMLElement
        expect(el.style.width).toBe('100px')
        expect(el.style.height).toBe('50px')
    })

    it('accepts string width', () => {
        const { container } = render(<Skeleton width="60%" />)
        const el = container.firstChild as HTMLElement
        expect(el.style.width).toBe('60%')
    })
})

describe('SkeletonCard', () => {
    it('renders without crashing', () => {
        const { container } = render(<SkeletonCard />)
        expect(container.firstChild).toBeInTheDocument()
    })

    it('renders avatar by default', () => {
        const { container } = render(<SkeletonCard showAvatar={true} />)
        // Should have multiple skeleton divs
        expect(container.querySelectorAll('.skeleton').length).toBeGreaterThan(1)
    })
})

describe('SkeletonGrid', () => {
    it('renders correct number of cells', () => {
        const { container } = render(<SkeletonGrid columns={4} rows={1} />)
        // 4 columns × 1 row = 4 cells, each with 3 skeletons
        expect(container.querySelectorAll('.skeleton').length).toBe(12)
    })
})
