import { useLayoutEffect, useState } from 'react'
import {
  resolveCardPageCapacity,
  type CardCapacityOptions,
} from '../lib/pagination.ts'

export function useCardPaginationCapacity(
  totalItems: number | null,
  options: CardCapacityOptions,
) {
  const [cardEl, setCardEl] = useState<HTMLDivElement | null>(null)
  const [cardHeight, setCardHeight] = useState<number>(0)

  // Synchronously sync outer flex card height whenever sibling banners/headers mount or unmount
  useLayoutEffect(() => {
    if (!cardEl) return
    const next = Math.floor(cardEl.clientHeight || cardEl.getBoundingClientRect().height)
    setCardHeight((prev) => (Math.abs(prev - next) >= 1 ? next : prev))
  })

  useLayoutEffect(() => {
    if (!cardEl) return undefined

    const measure = () => {
      const next = Math.floor(cardEl.clientHeight || cardEl.getBoundingClientRect().height)
      setCardHeight((prev) => (Math.abs(prev - next) >= 1 ? next : prev))
    }

    measure()
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure)
      return () => window.removeEventListener('resize', measure)
    }

    const observer = new ResizeObserver(() => measure())
    observer.observe(cardEl)
    return () => observer.disconnect()
  }, [cardEl])

  const { pageSize, showPagination } = resolveCardPageCapacity(
    cardHeight,
    totalItems,
    options,
  )

  return {
    cardRef: setCardEl,
    pageSize,
    showPagination,
    measured: cardHeight > 0,
  }
}
