import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { clearAllDrafts } from '../lib/useDraft'

afterEach(clearAllDrafts)
