import type { ReactNode, HTMLAttributes } from 'react';

interface Props extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode;
  clickable?: boolean;
}

export function Card({ children, clickable = false, className = '', ...props }: Props) {
  const cardClass = `card ${clickable ? 'clickable' : ''} ${className}`;
  return (
    <div className={cardClass.trim()} {...props}>
      {children}
    </div>
  );
}
