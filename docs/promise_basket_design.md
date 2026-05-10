# Promise Basket Color Design

## Core Concept

약속 바구니는 사용자가 대화에서 추출한 약속 후보를 잠시 담아두고 검토하는 공간이다.  
색상은 **따뜻한 정리함**, **부드러운 확정감**, **가벼운 알림성**을 중심으로 설계한다.

---

## 1. Main Palette

| Token | Color | Hex | Usage |
|---|---:|---:|---|
| `basket-primary` | Soft Apricot | `#F4A261` | 주요 버튼, 선택된 필터, 핵심 액션 |
| `basket-primary-dark` | Burnt Apricot | `#D9823B` | 버튼 pressed 상태, 강조 테두리 |
| `basket-primary-light` | Pale Apricot | `#FFE3C2` | 카드 배경, 약한 강조 영역 |
| `basket-secondary` | Warm Cream | `#FFF7EC` | 전체 배경, 빈 상태 배경 |
| `basket-accent` | Honey Gold | `#F6C453` | 알림 뱃지, 추천 표시, 가벼운 하이라이트 |

---

## 2. Background Colors

| Token | Color | Hex | Usage |
|---|---:|---:|---|
| `bg-page` | Warm Ivory | `#FFFBF4` | 약속 바구니 화면 전체 배경 |
| `bg-card` | Soft White | `#FFFFFF` | 약속 후보 카드 기본 배경 |
| `bg-card-warm` | Cream Card | `#FFF3E0` | 선택된 카드, 추천 카드 |
| `bg-elevated` | Warm Surface | `#FFF8ED` | bottom sheet, modal, floating panel |
| `bg-disabled` | Light Sand | `#EFE7DA` | 비활성 영역 |

---

## 3. Text Colors

| Token | Color | Hex | Usage |
|---|---:|---:|---|
| `text-primary` | Charcoal Brown | `#2F2924` | 제목, 핵심 텍스트 |
| `text-secondary` | Warm Gray Brown | `#6F6258` | 설명, 날짜, 장소 |
| `text-muted` | Dust Gray | `#A89C91` | 보조 정보, placeholder |
| `text-inverse` | White | `#FFFFFF` | primary 버튼 내부 텍스트 |
| `text-warning` | Amber Brown | `#9A6200` | 주의 메시지 |
| `text-danger` | Soft Red Brown | `#A94442` | 삭제, 실패, 충돌 위험 텍스트 |

---

## 4. Border Colors

| Token | Color | Hex | Usage |
|---|---:|---:|---|
| `border-default` | Warm Border | `#E8D9C7` | 카드 기본 테두리 |
| `border-strong` | Apricot Border | `#F4A261` | 선택 카드, active 상태 |
| `border-muted` | Pale Sand | `#F1E8DD` | 구분선, 약한 divider |
| `border-danger` | Muted Red | `#E8B4B0` | 충돌 카드, 삭제 확인 |
| `border-success` | Soft Green Border | `#B9D8C2` | 등록 가능 상태 |

---

## 5. Status Colors

| Status | Token | Background | Text | Border |
|---|---|---:|---:|---:|
| 등록 가능 | `status-ready` | `#EAF7ED` | `#2F6B3F` | `#B9D8C2` |
| 충돌 있음 | `status-conflict` | `#FFF0ED` | `#A94442` | `#E8B4B0` |
| 정보 부족 | `status-incomplete` | `#FFF6D9` | `#8A6400` | `#EFD47A` |
| 보류 | `status-pending` | `#F0EEF7` | `#5D547A` | `#D5CDE8` |
| 등록 완료 | `status-done` | `#E8F3FF` | `#2E5E8C` | `#B8D5F2` |

---

## 6. Button Colors

| Button Type | Background | Text | Border |
|---|---:|---:|---:|
| Primary | `#F4A261` | `#FFFFFF` | `#F4A261` |
| Primary Pressed | `#D9823B` | `#FFFFFF` | `#D9823B` |
| Secondary | `#FFF3E0` | `#7A4A1B` | `#F4C28C` |
| Ghost | `transparent` | `#6F6258` | `transparent` |
| Danger | `#FFF0ED` | `#A94442` | `#E8B4B0` |
| Disabled | `#EFE7DA` | `#A89C91` | `#EFE7DA` |

---

## 7. Filter Chip Colors

| State | Background | Text | Border |
|---|---:|---:|---:|
| Default | `#FFFFFF` | `#6F6258` | `#E8D9C7` |
| Active | `#F4A261` | `#FFFFFF` | `#F4A261` |
| Warning Active | `#FFF6D9` | `#8A6400` | `#EFD47A` |
| Conflict Active | `#FFF0ED` | `#A94442` | `#E8B4B0` |

---

## 8. Shadow Colors

| Token | Color | Usage |
|---|---:|---|
| `shadow-card` | `rgba(89, 62, 38, 0.08)` | 일반 약속 카드 |
| `shadow-floating` | `rgba(89, 62, 38, 0.14)` | 하단 고정 버튼, floating panel |
| `shadow-selected` | `rgba(244, 162, 97, 0.24)` | 선택된 카드 강조 |

---

## 9. Gradient Colors

### Warm Basket Gradient

```css
linear-gradient(135deg, #FFF7EC 0%, #FFE3C2 100%)
```

### Primary Action Gradient

```css
linear-gradient(135deg, #F4A261 0%, #F6C453 100%)
```

### Conflict Soft Gradient

```css
linear-gradient(135deg, #FFF0ED 0%, #FFE5DF 100%)
```

---

## 10. Recommended CSS Variables

```css
:root {
  --basket-primary: #F4A261;
  --basket-primary-dark: #D9823B;
  --basket-primary-light: #FFE3C2;
  --basket-secondary: #FFF7EC;
  --basket-accent: #F6C453;

  --bg-page: #FFFBF4;
  --bg-card: #FFFFFF;
  --bg-card-warm: #FFF3E0;
  --bg-elevated: #FFF8ED;
  --bg-disabled: #EFE7DA;

  --text-primary: #2F2924;
  --text-secondary: #6F6258;
  --text-muted: #A89C91;
  --text-inverse: #FFFFFF;
  --text-warning: #9A6200;
  --text-danger: #A94442;

  --border-default: #E8D9C7;
  --border-strong: #F4A261;
  --border-muted: #F1E8DD;
  --border-danger: #E8B4B0;
  --border-success: #B9D8C2;

  --status-ready-bg: #EAF7ED;
  --status-ready-text: #2F6B3F;
  --status-ready-border: #B9D8C2;

  --status-conflict-bg: #FFF0ED;
  --status-conflict-text: #A94442;
  --status-conflict-border: #E8B4B0;

  --status-incomplete-bg: #FFF6D9;
  --status-incomplete-text: #8A6400;
  --status-incomplete-border: #EFD47A;

  --status-pending-bg: #F0EEF7;
  --status-pending-text: #5D547A;
  --status-pending-border: #D5CDE8;

  --status-done-bg: #E8F3FF;
  --status-done-text: #2E5E8C;
  --status-done-border: #B8D5F2;

  --shadow-card: rgba(89, 62, 38, 0.08);
  --shadow-floating: rgba(89, 62, 38, 0.14);
  --shadow-selected: rgba(244, 162, 97, 0.24);
}
```
